package art.arcane.wormholes.chunk.presend;

import art.arcane.wormholes.service.WormholesTelemetry;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

final class ChunkArrivalDelivery<W, P> {
    private static final int MAX_PREPARATIONS = 128;
    private static final int MAX_CAPTURES = 32;
    private static final int MAX_PREPARED_PER_PLAYER = 4;
    private static final int MAX_RETRIES = 3;
    private static final long LIFETIME_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final ChunkPreSendPlatform<W, P> platform;
    private final Supplier<ChunkPreSendOptions> options;
    private final ConcurrentHashMap<P, Preparation<W, P>> preparations = new ConcurrentHashMap<>();
    private final Semaphore captures = new Semaphore(MAX_CAPTURES);
    private final Semaphore captureWork = new Semaphore(1);
    private long captureWindow;
    private long captureNanos;
    private long captureStarted;
    private volatile boolean closed;

    ChunkArrivalDelivery(ChunkPreSendPlatform<W, P> platform, Supplier<ChunkPreSendOptions> options) {
        this.platform = platform;
        this.options = options;
    }

    ChunkPreSendTicket<W, P> prepare(ChunkPreSendOrigin<W, P> origin, W world, int x, int z) {
        Objects.requireNonNull(origin, "origin");
        ChunkPreSendOptions active = options.get();
        if (closed || !active.usable() || world == null || !origin.playerUsable()) {
            return ChunkPreSendTicket.rejected(ChunkPreSendOutcome.SKIPPED_NO_BUDGET, origin.player());
        }
        synchronized (preparations) {
            for (Preparation<W, P> candidate : preparations.values()) {
                if (platform.nanoTime() > candidate.deadline) {
                    cancel(candidate.ticket);
                }
            }
            Preparation<W, P> existing = preparations.get(origin.player());
            if (existing != null && !existing.committed && existing.world.equals(world)
                && existing.x == x && existing.z == z) {
                return existing.ticket;
            }
            if (preparations.size() >= MAX_PREPARATIONS && !preparations.containsKey(origin.player())) {
                return ChunkPreSendTicket.rejected(ChunkPreSendOutcome.SKIPPED_NO_BUDGET, origin.player());
            }
            List<ChunkCoordinate> chunks = ChunkPreSendPlanner.ring(x, z,
                ChunkPreSendPlanner.effectiveRadius(active.radiusChunks(), origin.clientViewDistance()));
            ChunkPreSendTicket<W, P> ticket = new ChunkPreSendTicket<>(ChunkPreSendOutcome.PREPARED,
                origin.player(), origin.sourceWorld(), ChunkPreSendRollback.none(origin.sourceChunkX(), origin.sourceChunkZ()),
                0, chunks.size(), false);
            Preparation<W, P> preparation = new Preparation<>(ticket, world, x, z, chunks,
                platform.nanoTime() + LIFETIME_NANOS);
            Preparation<W, P> previous = preparations.put(origin.player(), preparation);
            if (previous != null) {
                previous.cancelled.set(true);
                releasePrepared(previous);
            }
            if (closed) {
                cancel(ticket);
                return ChunkPreSendTicket.rejected(ChunkPreSendOutcome.SKIPPED_NO_BUDGET, origin.player());
            }
            prepareNext(preparation);
            platform.scheduleForRegion(world, x, z, () -> {
                if (platform.nanoTime() >= preparation.deadline) {
                    cancel(ticket);
                }
            }, 101L);
            return ticket;
        }
    }

    boolean commit(ChunkPreSendTicket<W, P> ticket) {
        Preparation<W, P> preparation = preparations.get(ticket.player());
        if (preparation == null || preparation.ticket != ticket) {
            return false;
        }
        if (!ticket.claim()) {
            return false;
        }
        if (!valid(preparation)) {
            cancel(ticket);
            return false;
        }
        preparation.committed = true;
        if (!platform.announceViewCenter(ticket.player(), preparation.x, preparation.z)) {
            cancel(ticket);
            return false;
        }
        drain(preparation);
        return true;
    }

    void cancel(ChunkPreSendTicket<W, P> ticket) {
        Preparation<W, P> preparation = preparations.get(ticket.player());
        if (preparation != null && preparation.ticket == ticket) {
            preparation.cancelled.set(true);
            preparations.remove(ticket.player(), preparation);
            releasePrepared(preparation);
        }
    }

    void shutdown() {
        closed = true;
        for (Preparation<W, P> preparation : preparations.values()) {
            preparation.cancelled.set(true);
            releasePrepared(preparation);
        }
        preparations.clear();
    }

    void disconnected(P player) {
        Preparation<W, P> preparation = preparations.get(player);
        if (preparation != null) {
            cancel(preparation.ticket);
        }
    }

    private boolean active(Preparation<W, P> preparation) {
        return !closed && !preparation.cancelled.get()
            && preparations.get(preparation.ticket.player()) == preparation
            && platform.nanoTime() <= preparation.deadline;
    }

    private boolean valid(Preparation<W, P> preparation) {
        P player = preparation.ticket.player();
        return active(preparation) && platform.online(player)
            && Objects.equals(preparation.world, platform.world(player))
            && platform.chunkX(player) == preparation.x && platform.chunkZ(player) == preparation.z;
    }

    private void drain(Preparation<W, P> preparation) {
        if (!valid(preparation)) {
            cancel(preparation.ticket);
            return;
        }
        ChunkPreSendOptions budget = options.get();
        long started = platform.nanoTime();
        int inspected = 0;
        while (preparation.index < preparation.chunks.size()) {
            if (!active(preparation)) {
                cancel(preparation.ticket);
                return;
            }
            if (!budget.usable() || inspected >= budget.maxChunks()
                || inspected > 0 && platform.nanoTime() - started >= budget.budgetNanos()) {
                resume(preparation);
                return;
            }
            ChunkCoordinate coordinate = preparation.chunks.get(preparation.index);
            inspected++;
            if (platform.alreadySent(preparation.ticket.player(), coordinate.x(), coordinate.z())) {
                removePrepared(preparation, preparation.index);
                advance(preparation);
                continue;
            }
            ChunkPreparedPacket<P> prepared = preparation.prepared.get(preparation.index);
            if (prepared != null) {
                if (!prepared.valid()) {
                    removePrepared(preparation, preparation.index);
                } else if (!prepared.ready()) {
                    resume(preparation);
                    return;
                } else {
                    ChunkPreparedPacket<P> selected = takePrepared(preparation, preparation.index);
                    if (selected == null) {
                        cancel(preparation.ticket);
                        return;
                    }
                    deliver(preparation, selected);
                    continue;
                }
            }
            if (!platform.regionOwned(preparation.world, coordinate.x(), coordinate.z(), coordinate.x(), coordinate.z())) {
                if (!platform.scheduleForRegion(preparation.world, coordinate.x(), coordinate.z(),
                    () -> captureElsewhere(preparation, coordinate), 1L)) {
                    cancel(preparation.ticket);
                }
                return;
            }
            if (!captures.tryAcquire()) {
                resume(preparation);
                return;
            }
            ChunkPreparedPacket<P> packet;
            if (!beginCapture()) {
                captures.release();
                resume(preparation);
                return;
            }
            try {
                packet = platform.chunkLoaded(preparation.world, coordinate.x(), coordinate.z())
                    ? platform.prepareChunk(preparation.world, coordinate.x(), coordinate.z()) : null;
            } catch (RuntimeException | Error failure) {
                captures.release();
                cancel(preparation.ticket);
                throw failure;
            } finally {
                endCapture();
            }
            if (packet != null && !packet.ready()) {
                cachePrepared(preparation, preparation.index, packet);
                resume(preparation);
                return;
            }
            if (packet == null) {
                captures.release();
            }
            if (!deliver(preparation, packet)) {
                resume(preparation);
                return;
            }
        }
        releaseCached(preparation);
        if (preparation.writing.isEmpty()) {
            preparations.remove(preparation.ticket.player(), preparation);
        }
    }

    private void captureElsewhere(Preparation<W, P> preparation, ChunkCoordinate coordinate) {
        if (!active(preparation)) {
            cancel(preparation.ticket);
            return;
        }
        if (!captures.tryAcquire()) {
            resume(preparation);
            return;
        }
        if (!beginCapture()) {
            captures.release();
            resume(preparation);
            return;
        }
        ChunkPreparedPacket<P> packet;
        try {
            packet = active(preparation)
                && platform.regionOwned(preparation.world, coordinate.x(), coordinate.z(), coordinate.x(), coordinate.z())
                && platform.chunkLoaded(preparation.world, coordinate.x(), coordinate.z())
                ? platform.prepareChunk(preparation.world, coordinate.x(), coordinate.z()) : null;
        } catch (RuntimeException | Error failure) {
            captures.release();
            cancel(preparation.ticket);
            throw failure;
        } finally {
            endCapture();
        }
        boolean captured = packet != null;
        if (captured) {
            cachePrepared(preparation, preparation.index, packet);
        } else {
            captures.release();
        }
        if (!platform.scheduleForPlayer(preparation.ticket.player(), () -> {
            if (!valid(preparation)) {
                cancel(preparation.ticket);
                return;
            }
            if (captured) {
                drain(preparation);
            } else {
                deliver(preparation, null);
                resume(preparation);
            }
        }, () -> cancel(preparation.ticket), 0L)) {
            cancel(preparation.ticket);
        }
    }

    private boolean deliver(Preparation<W, P> preparation, ChunkPreparedPacket<P> packet) {
        if (packet == null) {
            if (++preparation.retries >= MAX_RETRIES) {
                advance(preparation);
            }
            return false;
        }
        AtomicBoolean closed = new AtomicBoolean();
        synchronized (preparation) {
            if (!active(preparation)) {
                packet.close();
                captures.release();
                return false;
            }
            preparation.writing.put(packet, closed);
        }
        try {
            packet.send(preparation.ticket.player()).whenComplete((written, failure) -> {
                finishWrite(preparation, packet, closed);
                if (failure != null || !Boolean.TRUE.equals(written)) {
                    WormholesTelemetry.countFailure("ARRIVAL_CHUNK_WRITE_FAILED");
                    cancel(preparation.ticket);
                }
            });
        } catch (RuntimeException | Error failure) {
            finishWrite(preparation, packet, closed);
            cancel(preparation.ticket);
            throw failure;
        }
        advance(preparation);
        return true;
    }

    private void prepareNext(Preparation<W, P> preparation) {
        if (!active(preparation) || preparation.committed || preparation.captureIndex >= preparation.chunks.size()
            || preparation.prepared.size() >= MAX_PREPARED_PER_PLAYER) {
            return;
        }
        int index = preparation.captureIndex++;
        ChunkCoordinate coordinate = preparation.chunks.get(index);
        if (!platform.scheduleForRegion(preparation.world, coordinate.x(), coordinate.z(), () -> {
            if (!captures.tryAcquire()) {
                preparation.captureIndex--;
                prepareNext(preparation);
                return;
            }
            if (!beginCapture()) {
                captures.release();
                preparation.captureIndex--;
                prepareNext(preparation);
                return;
            }
            ChunkPreparedPacket<P> packet = null;
            try {
                if (active(preparation) && !preparation.committed
                    && platform.regionOwned(preparation.world, coordinate.x(), coordinate.z(), coordinate.x(), coordinate.z())
                    && platform.chunkLoaded(preparation.world, coordinate.x(), coordinate.z())) {
                    packet = platform.prepareChunk(preparation.world, coordinate.x(), coordinate.z());
                }
                if (packet != null) {
                    cachePrepared(preparation, index, packet);
                } else {
                    captures.release();
                }
            } catch (RuntimeException | Error failure) {
                captures.release();
                cancel(preparation.ticket);
                throw failure;
            } finally {
                endCapture();
            }
            prepareNext(preparation);
        }, 1L)) {
            cancel(preparation.ticket);
        }
    }

    private void cachePrepared(Preparation<W, P> preparation, int index, ChunkPreparedPacket<P> packet) {
        synchronized (preparation) {
            if (!active(preparation) || preparation.prepared.putIfAbsent(index, packet) != null) {
                packet.close();
                captures.release();
            }
        }
    }

    private synchronized boolean beginCapture() {
        long now = platform.nanoTime();
        long window = now / TimeUnit.MILLISECONDS.toNanos(50);
        if (window != captureWindow) {
            captureWindow = window;
            captureNanos = 0L;
        }
        if (captureNanos >= options.get().budgetNanos() || !captureWork.tryAcquire()) {
            return false;
        }
        captureStarted = now;
        return true;
    }

    private synchronized void endCapture() {
        captureNanos += Math.max(0L, platform.nanoTime() - captureStarted);
        captureWork.release();
    }

    private void removePrepared(Preparation<W, P> preparation, int index) {
        ChunkPreparedPacket<P> packet = preparation.prepared.remove(index);
        if (packet != null) {
            packet.close();
            captures.release();
        }
    }

    private ChunkPreparedPacket<P> takePrepared(Preparation<W, P> preparation, int index) {
        synchronized (preparation) {
            return active(preparation) ? preparation.prepared.remove(index) : null;
        }
    }

    private void releasePrepared(Preparation<W, P> preparation) {
        synchronized (preparation) {
            releaseCached(preparation);
            preparation.writing.forEach((packet, released) -> {
                if (released.compareAndSet(false, true)) {
                    packet.close();
                }
            });
        }
    }

    private void releaseCached(Preparation<W, P> preparation) {
        synchronized (preparation) {
            for (Integer index : preparation.prepared.keySet()) {
                removePrepared(preparation, index);
            }
        }
    }

    private void finishWrite(Preparation<W, P> preparation, ChunkPreparedPacket<P> packet, AtomicBoolean closed) {
        if (preparation.writing.remove(packet, closed)) {
            if (closed.compareAndSet(false, true)) {
                packet.close();
            }
            captures.release();
            if (preparation.index >= preparation.chunks.size() && preparation.writing.isEmpty()) {
                preparations.remove(preparation.ticket.player(), preparation);
            }
        }
    }

    private void advance(Preparation<W, P> preparation) {
        preparation.index++;
        preparation.retries = 0;
    }

    private void resume(Preparation<W, P> preparation) {
        if (!active(preparation) || !platform.scheduleForPlayer(preparation.ticket.player(), () -> drain(preparation),
            () -> cancel(preparation.ticket), 1L)) {
            cancel(preparation.ticket);
        }
    }

    private static final class Preparation<W, P> {
        private final ChunkPreSendTicket<W, P> ticket;
        private final W world;
        private final int x;
        private final int z;
        private final List<ChunkCoordinate> chunks;
        private final long deadline;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final ConcurrentHashMap<Integer, ChunkPreparedPacket<P>> prepared = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<ChunkPreparedPacket<P>, AtomicBoolean> writing = new ConcurrentHashMap<>();
        private volatile boolean committed;
        private int captureIndex;
        private volatile int index;
        private int retries;

        private Preparation(ChunkPreSendTicket<W, P> ticket, W world, int x, int z,
                            List<ChunkCoordinate> chunks, long deadline) {
            this.ticket = ticket;
            this.world = world;
            this.x = x;
            this.z = z;
            this.chunks = chunks;
            this.deadline = deadline;
        }
    }
}
