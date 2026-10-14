package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.clientview.ClientViewPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CrossingBenchTap {
    private static final Object LOCK = new Object();
    private static final long ATTRIBUTED_WAIT_NANOS = 1_000_000L;
    private static final int ATTRIBUTION_DEPTH = 48;
    private static volatile boolean active;
    private static volatile boolean crossed;
    private static Recording recording;
    private static long frameStarted;
    private static long frameIdle;
    private static long limiterStarted;

    private CrossingBenchTap() {
    }

    public static boolean active() {
        return active;
    }

    static void open() {
        synchronized (LOCK) {
            Instant now = Instant.now();
            recording = new Recording(System.nanoTime(), now.getEpochSecond() * 1_000_000_000L + now.getNano());
            crossed = false;
            active = true;
        }
    }

    static Recording close() {
        synchronized (LOCK) {
            active = false;
            Recording closed = recording;
            recording = null;
            return closed;
        }
    }

    public static void serverCycleStarted(int tick) {
        if (!active) {
            return;
        }
        long now = System.nanoTime();
        synchronized (LOCK) {
            if (recording != null) {
                recording.cycles.add(new ServerCycle(tick, now));
            }
        }
    }

    public static void serverTickStarted() {
        if (!active) {
            return;
        }
        long now = System.nanoTime();
        synchronized (LOCK) {
            ServerCycle cycle = current();
            if (cycle != null && cycle.tickStart == 0L) {
                cycle.packetsNanos = now - cycle.start;
                cycle.tickStart = now;
            }
        }
    }

    public static void serverTickEnded() {
        if (!active) {
            return;
        }
        long now = System.nanoTime();
        synchronized (LOCK) {
            ServerCycle cycle = current();
            if (cycle != null && cycle.tickStart != 0L && cycle.tickNanos == 0L) {
                cycle.tickNanos = now - cycle.tickStart;
            }
        }
    }

    public static void serverTask(long nanos) {
        if (!active) {
            return;
        }
        synchronized (LOCK) {
            ServerCycle cycle = current();
            if (cycle != null) {
                cycle.tasksNanos += nanos;
                cycle.tasks++;
            }
        }
    }

    public static void wormholesTick(long nanos) {
        if (!active) {
            return;
        }
        synchronized (LOCK) {
            ServerCycle cycle = current();
            if (cycle != null) {
                cycle.wormholesNanos += nanos;
            }
        }
    }

    public static void wormholesInbound(long nanos) {
        if (!active) {
            return;
        }
        synchronized (LOCK) {
            ServerCycle cycle = current();
            if (cycle != null) {
                cycle.inboundNanos += nanos;
            }
        }
    }

    public static void seamlessMove(long started, long nanos, String from, String to, boolean moved) {
        if (!active) {
            return;
        }
        synchronized (LOCK) {
            if (recording != null) {
                ServerCycle cycle = current();
                recording.moves.add(new Move(started, nanos, from, to, moved, cycle == null ? -1 : cycle.tick));
            }
        }
    }

    public static void chunkLoaded(ServerLevel level) {
        if (!active) {
            return;
        }
        String dimension = level.dimension().identifier().toString();
        synchronized (LOCK) {
            if (recording != null) {
                recording.chunkLoads.merge(dimension + (crossed ? " after" : " before"), 1L, Long::sum);
            }
        }
    }

    public static void chunkWait(long nanos) {
        if (!active) {
            return;
        }
        String attribution = nanos >= ATTRIBUTED_WAIT_NANOS ? attribution() : "under 1 ms";
        synchronized (LOCK) {
            if (recording == null) {
                return;
            }
            long[] totals = recording.chunkWaits.computeIfAbsent(attribution, ignored -> new long[3]);
            totals[0]++;
            totals[1] += nanos;
            totals[2] = Math.max(totals[2], nanos);
            ServerCycle cycle = current();
            if (cycle != null) {
                cycle.chunkWaitNanos += nanos;
            }
        }
    }

    public static void sent(Packet<?> packet) {
        if (!active) {
            return;
        }
        String type = packet.type().id().toString();
        long bytes = 0L;
        if (packet instanceof ClientboundCustomPayloadPacket custom) {
            type = "custom " + custom.payload().type().id();
            if (custom.payload() instanceof ClientViewPayload payload) {
                bytes = payload.data().length;
            }
        }
        add(crossed ? Bucket.SENT_AFTER : Bucket.SENT_BEFORE, type, bytes);
    }

    public static void encoded(Packet<?> packet, int bytes) {
        if (active) {
            add(crossed ? Bucket.ENCODED_AFTER : Bucket.ENCODED_BEFORE, packet.type().id().toString(), bytes);
        }
    }

    public static void routed(Packet<?> packet, int bytes) {
        if (active) {
            add(crossed ? Bucket.ROUTED_AFTER : Bucket.ROUTED_BEFORE, packet.type().id().toString(), Math.max(0, bytes));
        }
    }

    public static void frameStarted() {
        if (active) {
            frameStarted = System.nanoTime();
            frameIdle = 0L;
        }
    }

    public static void limiterStarted() {
        if (active) {
            limiterStarted = System.nanoTime();
        }
    }

    public static void limiterEnded() {
        if (active && limiterStarted != 0L) {
            frameIdle += System.nanoTime() - limiterStarted;
            limiterStarted = 0L;
        }
    }

    public static void frameEnded(Minecraft minecraft) {
        if (!active || frameStarted == 0L) {
            return;
        }
        long now = System.nanoTime();
        boolean ready = minecraft.level != null && MainRendererSections.terrainComplete(minecraft);
        int visible = minecraft.level == null ? 0 : MainRendererSections.visible(minecraft);
        String dimension = minecraft.level == null ? "" : minecraft.level.dimension().identifier().toString();
        int level = System.identityHashCode(minecraft.level);
        synchronized (LOCK) {
            if (recording != null) {
                recording.frames.add(new Frame(frameStarted, now - frameStarted - frameIdle, frameIdle, level, dimension, ready, visible));
            }
        }
        frameStarted = 0L;
    }

    public static void clientCrossing(long started, long nanos, Minecraft minecraft, String from, boolean accepted) {
        if (!active) {
            return;
        }
        String to = minecraft.level == null ? "" : minecraft.level.dimension().identifier().toString();
        synchronized (LOCK) {
            if (recording == null) {
                return;
            }
            if (!accepted) {
                recording.clientDeclines++;
                return;
            }
            recording.clientCrossings.add(new ClientCrossing(started, nanos, recording.frames.size(), from, to));
            crossed = true;
        }
    }

    private static ServerCycle current() {
        if (recording == null || recording.cycles.isEmpty()) {
            return null;
        }
        return recording.cycles.getLast();
    }

    private static void add(Bucket bucket, String type, long bytes) {
        synchronized (LOCK) {
            if (recording == null) {
                return;
            }
            long[] totals = recording.packets.get(bucket).computeIfAbsent(type, ignored -> new long[2]);
            totals[0]++;
            totals[1] += bytes;
        }
    }

    private static String attribution() {
        List<String> frames = StackWalker.getInstance().walk(stream -> stream.limit(ATTRIBUTION_DEPTH)
            .map(frame -> frame.getClassName() + "." + frame.getMethodName()).toList());
        for (String frame : frames) {
            if (frame.startsWith("art.arcane.wormholes.clientgametest.")) {
                continue;
            }
            if (frame.startsWith("art.arcane.") || frame.contains("wormholes$") || frame.contains("$wormholes")) {
                return "wormholes " + frame;
            }
        }
        for (String frame : frames) {
            if (!frame.startsWith("net.minecraft.server.level.ServerChunkCache") && !frame.startsWith("art.arcane.wormholes.clientgametest.")
                && !frame.startsWith("java.") && !frame.startsWith("com.llamalad7.")) {
                return "vanilla " + frame;
            }
        }
        return "vanilla unknown";
    }

    enum Bucket {
        SENT_BEFORE,
        SENT_AFTER,
        ENCODED_BEFORE,
        ENCODED_AFTER,
        ROUTED_BEFORE,
        ROUTED_AFTER
    }

    static final class Recording {
        final long openNanos;
        final long openEpochNanos;
        final List<ServerCycle> cycles = new ArrayList<>(512);
        final List<Move> moves = new ArrayList<>();
        final List<Frame> frames = new ArrayList<>(2048);
        final List<ClientCrossing> clientCrossings = new ArrayList<>();
        final Map<String, Long> chunkLoads = new HashMap<>();
        final Map<String, long[]> chunkWaits = new HashMap<>();
        final Map<Bucket, Map<String, long[]>> packets = new HashMap<>();
        int clientDeclines;

        private Recording(long openNanos, long openEpochNanos) {
            this.openNanos = openNanos;
            this.openEpochNanos = openEpochNanos;
            for (Bucket bucket : Bucket.values()) {
                packets.put(bucket, new HashMap<>());
            }
        }

        long epochNanos(long nanoTime) {
            return openEpochNanos + (nanoTime - openNanos);
        }
    }

    static final class ServerCycle {
        final int tick;
        final long start;
        long packetsNanos;
        long tickStart;
        long tickNanos;
        long tasksNanos;
        int tasks;
        long wormholesNanos;
        long inboundNanos;
        long chunkWaitNanos;

        private ServerCycle(int tick, long start) {
            this.tick = tick;
            this.start = start;
        }

        long busyNanos() {
            return packetsNanos + tickNanos + tasksNanos;
        }
    }

    record Move(long start, long nanos, String from, String to, boolean moved, int tick) {
    }

    record Frame(long start, long nanos, long idleNanos, int level, String dimension, boolean ready, int visible) {
    }

    record ClientCrossing(long start, long nanos, int frame, String from, String to) {
    }
}
