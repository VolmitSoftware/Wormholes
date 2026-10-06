package art.arcane.wormholes.render.client.session;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.wormholes.network.client.ClientTravelHash;
import art.arcane.optics.view.WorldChangeTracker;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

public final class ClientPreparedTravelServer implements WorldChangeTracker.ChangeListener, AutoCloseable {
    private static final int MAX_RETAINED_COLUMNS = 256;
    private static final int MAX_RETAINED_BYTES = ViewStreamLimits.MAX_TRAVEL_BYTES;
    private static final int MAX_SENT_BARRIERS = 16;
    private static final long PROBE_BUDGET_NANOS = 2_000_000L;
    private static final long PROBE_INTERVAL_MILLIS = 1_000L / ViewStreamLimits.DEFAULT_TICK_RATE;
    private static final long PROBE_TIMEOUT_MILLIS = 1_000L;
    private static final long CROSSING_TIMEOUT_MILLIS = 2_000L;
    private final HashMap<ClientViewMessage.TravelCoordinate, Column> columns = new HashMap<>();
    private final LinkedHashMap<SnapshotKey, Payload> retained = new LinkedHashMap<>(16, 0.75F, true);
    private int retainedBytes;
    private final ArrayDeque<Long> emittedBarriers = new ArrayDeque<>(MAX_SENT_BARRIERS);
    private final ArrayDeque<Long> acknowledgedBarriers = new ArrayDeque<>(MAX_SENT_BARRIERS);
    private final ArrayDeque<Probe> pendingProbes = new ArrayDeque<>(ViewStreamLimits.MAX_TRAVEL_REUSE_PROBES_PER_TICK);
    private long probeWindowMillis = Long.MIN_VALUE;
    private int probesInWindow;
    private ClientViewMessage.TravelCross pendingCross;
    private long crossDeadline;
    private boolean crossClaimed;
    private long automaticDeadline;
    private ClientViewMessage.TravelBegin begin;
    private long deadline;
    private long contentRevision;
    private long sentRevision;
    private long readyRevision;
    private int bytes;
    private int cursor;
    private int captureCursor;
    private boolean announced;
    private boolean reuseSelected;
    private WorldChangeTracker changes;
    private UUID destinationWorld;
    private boolean worldInvalidated;

    public synchronized void begin(ClientViewMessage.TravelBegin value, long nowMillis) {
        clear();
        begin = Objects.requireNonNull(value);
        deadline = nowMillis + value.expiresMillis();
        contentRevision = 1L;
    }

    public synchronized void reuseSelected(boolean value) {
        reuseSelected = value;
    }

    public synchronized boolean cached(ClientViewMessage.TravelCached value) {
        releaseProbe(value);
        if (!reuseSelected || begin == null || worldInvalidated || !begin.token().equals(value.token())
            || begin.generation() != value.generation()) {
            return false;
        }
        Column column = columns.get(new ClientViewMessage.TravelCoordinate(value.chunkX(), value.chunkZ()));
        if (column == null || !column.valid || column.revision != value.revision() || !column.probed
            || !Arrays.equals(column.hash(), value.hash())) {
            return false;
        }
        if (column.cacheAnswered) {
            return column.reused == value.available();
        }
        column.cacheAnswered = true;
        column.reused = value.available();
        return true;
    }

    public synchronized Optional<ClientViewMessage.TravelBegin> preparing() {
        return Optional.ofNullable(begin);
    }

    public synchronized void watchWorld(WorldChangeTracker tracker, UUID world) {
        Objects.requireNonNull(tracker);
        Objects.requireNonNull(world);
        if (begin == null) {
            throw new IllegalStateException("Prepared travel has not begun");
        }
        if (changes != null) {
            changes.removeListener(this);
        }
        changes = tracker;
        destinationWorld = world;
        changes.addListener(this);
    }

    @Override
    public void blockChanged(UUID worldId, long blockKey) {
    }

    @Override
    public void columnChanged(UUID worldId, int chunkX, int chunkZ) {
    }

    @Override
    public synchronized void close() {
        clear();
        retained.clear();
        retainedBytes = 0;
        pendingProbes.clear();
    }

    @Override
    public synchronized void worldCleared(UUID worldId) {
        retained.entrySet().removeIf(entry -> {
            if (!entry.getKey().world().equals(worldId)) {
                return false;
            }
            retainedBytes -= entry.getValue().bytes.length;
            return true;
        });
        if (worldId.equals(destinationWorld)) {
            worldInvalidated = true;
            readyRevision = 0L;
        }
    }

    public synchronized boolean column(ClientViewMessage.TravelCoordinate position, int revision, byte[] payload) {
        Objects.requireNonNull(position);
        Objects.requireNonNull(payload);
        if (begin == null || worldInvalidated || !begin.chunks().contains(position) || revision <= 0 || payload.length == 0
            || payload.length > ViewStreamLimits.MAX_TRAVEL_CHUNK_BYTES) {
            return false;
        }
        Column previous = columns.get(position);
        if (previous != null && revision <= previous.revision) {
            return false;
        }
        int nextBytes = bytes - (previous == null ? 0 : previous.payload.length) + payload.length;
        if (nextBytes > ViewStreamLimits.MAX_TRAVEL_BYTES) {
            return false;
        }
        columns.put(position, new Column(revision, snapshot(position, payload)));
        bytes = nextBytes;
        contentRevision++;
        return true;
    }

    private Payload snapshot(ClientViewMessage.TravelCoordinate coordinate, byte[] current) {
        if (destinationWorld == null) {
            return new Payload(current.clone());
        }
        SnapshotKey key = new SnapshotKey(destinationWorld, begin.world(), coordinate);
        Payload prior = retained.get(key);
        if (prior != null && Arrays.equals(prior.bytes, current)) {
            return prior;
        }
        Payload next = new Payload(current.clone());
        retained.put(key, next);
        retainedBytes += next.bytes.length - (prior == null ? 0 : prior.bytes.length);
        while (retained.size() > MAX_RETAINED_COLUMNS || retainedBytes > MAX_RETAINED_BYTES) {
            retainedBytes -= retained.pollFirstEntry().getValue().bytes.length;
        }
        return next;
    }

    public synchronized void invalidate(ClientViewMessage.TravelCoordinate position) {
        Column column = columns.get(position);
        if (column != null && column.valid) {
            column.valid = false;
            contentRevision++;
        }
    }

    public synchronized boolean needs(ClientViewMessage.TravelCoordinate position) {
        Column column = columns.get(position);
        return begin != null && !worldInvalidated && (column == null || !column.valid);
    }

    public synchronized ClientViewMessage.TravelCoordinate nextCapture() {
        if (begin == null || worldInvalidated) {
            return null;
        }
        int remaining = begin.chunks().size();
        while (remaining-- > 0) {
            if (captureCursor >= begin.chunks().size()) {
                captureCursor = 0;
            }
            ClientViewMessage.TravelCoordinate coordinate = begin.chunks().get(captureCursor++);
            Column column = columns.get(coordinate);
            if (column == null || !column.valid) {
                return coordinate;
            }
        }
        return null;
    }

    public synchronized int nextRevision(ClientViewMessage.TravelCoordinate position) {
        Column column = columns.get(position);
        return column == null ? 1 : Math.incrementExact(column.revision);
    }

    public synchronized void tick(long nowMillis, int byteBudget, Predicate<ClientViewMessage> sender) {
        Objects.requireNonNull(sender);
        if (begin == null) {
            return;
        }
        if (worldInvalidated || nowMillis >= deadline || pendingCross != null && nowMillis >= crossDeadline) {
            sender.test(new ClientViewMessage.TravelCancel(begin.token(), begin.generation()));
            clear();
            return;
        }
        if (!announced) {
            if (!sender.test(begin)) {
                return;
            }
            announced = true;
        }
        int remaining = Math.max(0, byteBudget);
        long probeStarted = System.nanoTime();
        int probedColumns = 0;
        if (probeWindowMillis == Long.MIN_VALUE || nowMillis - probeWindowMillis >= PROBE_INTERVAL_MILLIS) {
            probeWindowMillis = nowMillis;
            probesInWindow = 0;
        }
        int checks = begin.chunks().size();
        while (checks-- > 0 && remaining >= ViewStreamLimits.TRAVEL_REUSE_BYTES) {
            if (cursor >= begin.chunks().size()) {
                cursor = 0;
            }
            ClientViewMessage.TravelCoordinate position = begin.chunks().get(cursor++);
            Column column = columns.get(position);
            if (column == null || !column.valid || column.sent()) {
                continue;
            }
            if (reuseSelected && !column.cacheAnswered) {
                if (!column.probed) {
                    if (pendingProbes.size() >= ViewStreamLimits.MAX_TRAVEL_REUSE_PROBES_PER_TICK) {
                        if (nowMillis - pendingProbes.getFirst().sentMillis() < PROBE_TIMEOUT_MILLIS) {
                            continue;
                        }
                        column.cacheAnswered = true;
                    } else {
                        if (probedColumns >= ViewStreamLimits.MAX_TRAVEL_REUSE_PROBES_PER_TICK
                            || probesInWindow >= ViewStreamLimits.MAX_TRAVEL_REUSE_PROBES_PER_TICK
                            || probedColumns > 0 && System.nanoTime() - probeStarted >= PROBE_BUDGET_NANOS) {
                            continue;
                        }
                        probedColumns++;
                        column.probed = true;
                        column.probedAt = nowMillis;
                        Probe probe = new Probe(new ClientViewMessage.TravelReuse(begin.token(), begin.generation(), position.x(),
                            position.z(), column.revision, column.hash()), nowMillis);
                        pendingProbes.addLast(probe);
                        if (!sender.test(probe.offer())) {
                            pendingProbes.remove(probe);
                            column.probed = false;
                            return;
                        }
                        probesInWindow++;
                        remaining -= ViewStreamLimits.TRAVEL_REUSE_BYTES;
                        continue;
                    }
                } else if (nowMillis - column.probedAt >= PROBE_TIMEOUT_MILLIS) {
                    column.cacheAnswered = true;
                }
                if (!column.cacheAnswered) {
                    continue;
                }
            }
            if (remaining < ViewStreamLimits.TRAVEL_FRAGMENT_BYTES) {
                continue;
            }
            int fragments = (column.payload.length + ViewStreamLimits.TRAVEL_FRAGMENT_BYTES - 1)
                / ViewStreamLimits.TRAVEL_FRAGMENT_BYTES;
            int offset = column.fragment * ViewStreamLimits.TRAVEL_FRAGMENT_BYTES;
            int length = Math.min(ViewStreamLimits.TRAVEL_FRAGMENT_BYTES, column.payload.length - offset);
            byte[] payload = new byte[length];
            System.arraycopy(column.payload, offset, payload, 0, length);
            ClientViewMessage.TravelChunk chunk = new ClientViewMessage.TravelChunk(begin.token(), begin.generation(),
                position.x(), position.z(), column.revision, column.fragment, fragments, column.payload.length, payload);
            if (!sender.test(chunk)) {
                return;
            }
            remaining -= length;
            column.fragment++;
        }
        if (sentRevision != contentRevision && complete()) {
            List<ClientViewMessage.TravelChunkRevision> manifest = new ArrayList<>(begin.chunks().size());
            for (ClientViewMessage.TravelCoordinate position : begin.chunks()) {
                manifest.add(new ClientViewMessage.TravelChunkRevision(position.x(), position.z(), columns.get(position).revision));
            }
            if (sender.test(new ClientViewMessage.TravelEnd(begin.token(), begin.generation(), contentRevision, manifest))) {
                sentRevision = contentRevision;
                if (emittedBarriers.size() == MAX_SENT_BARRIERS) {
                    emittedBarriers.removeFirst();
                }
                emittedBarriers.addLast(contentRevision);
            }
        }
    }

    public synchronized boolean ready(ClientViewMessage.TravelReady value) {
        if (begin == null || worldInvalidated || !begin.token().equals(value.token()) || begin.generation() != value.generation()
            || !emittedBarriers.contains(value.contentRevision())) {
            return false;
        }
        if (!acknowledgedBarriers.contains(value.contentRevision())) {
            if (acknowledgedBarriers.size() == MAX_SENT_BARRIERS) {
                acknowledgedBarriers.removeFirst();
            }
            acknowledgedBarriers.addLast(value.contentRevision());
        }
        readyRevision = Math.max(readyRevision, value.contentRevision());
        return true;
    }

    public synchronized boolean requestCross(ClientViewMessage.TravelCross value, long nowMillis) {
        if (!acknowledged(value) || pendingCross != null || nowMillis >= deadline) {
            return false;
        }
        pendingCross = value;
        crossDeadline = nowMillis + CROSSING_TIMEOUT_MILLIS;
        return true;
    }

    public synchronized boolean readyRoute(UUID sourcePortal, long nowMillis) {
        return begin != null && !worldInvalidated && nowMillis < deadline && readyRevision > 0L
            && begin.sourcePortal().equals(sourcePortal);
    }

    public synchronized AutomaticCross automaticCross(UUID sourcePortal, long nowMillis) {
        if (!readyRoute(sourcePortal, nowMillis)) {
            return AutomaticCross.ORDINARY;
        }
        if (automaticDeadline == 0L) {
            automaticDeadline = nowMillis + CROSSING_TIMEOUT_MILLIS;
        }
        return nowMillis < automaticDeadline ? AutomaticCross.DEFER : AutomaticCross.FALLBACK;
    }

    public synchronized boolean crossing() {
        return pendingCross != null && crossClaimed;
    }

    public enum AutomaticCross {
        ORDINARY, DEFER, FALLBACK
    }

    public synchronized Optional<ClientViewMessage.TravelCross> takeCross() {
        if (pendingCross == null || crossClaimed) {
            return Optional.empty();
        }
        crossClaimed = true;
        return Optional.of(pendingCross);
    }

    public synchronized boolean validCross(ClientViewMessage.TravelCross value, Authority authority, long nowMillis) {
        Objects.requireNonNull(authority);
        if (!acknowledged(value) || nowMillis >= deadline || nowMillis >= crossDeadline
            || !begin.sourceWorld().equals(authority.world())
            || !sameSurface(begin.sourceGeometry(), authority.geometry())) {
            return false;
        }
        Vec3d feet = new Vec3d(value.sourcePose().x(), value.sourcePose().y(), value.sourcePose().z());
        Vec3d observed = new Vec3d(authority.pose().x(), authority.pose().y(), authority.pose().z());
        double speed = authority.velocity().distance(new Vec3d(0, 0, 0));
        double tolerance = Math.clamp(0.75D + speed * 3.0D, 0.75D, 2.0D);
        if (feet.distance(observed) > tolerance || value.previousEye().distance(value.currentEye()) > 4.0D
            || value.previousEye().distance(observed.add(new Vec3d(0, authority.eyeHeight(), 0))) > tolerance + 1.0D
            || value.currentEye().distance(feet.add(new Vec3d(0, authority.eyeHeight(), 0))) > 0.125D) {
            return false;
        }
        ApertureDescriptor geometry = authority.geometry();
        double previous = geometry.signedDistance(value.previousEye().x(), value.previousEye().y(), value.previousEye().z());
        double current = geometry.signedDistance(value.currentEye().x(), value.currentEye().y(), value.currentEye().z());
        if (previous == current || previous * current > 0.0D) {
            return false;
        }
        Vec3d intersection = value.previousEye().add(value.currentEye().subtract(value.previousEye())
            .multiply(previous / (previous - current)));
        return geometry.aperture().contains(intersection);
    }

    public synchronized void unavailable(ClientViewMessage.TravelCoordinate position) {
        invalidate(position);
        readyRevision = 0L;
        emittedBarriers.clear();
        acknowledgedBarriers.clear();
        pendingCross = null;
        crossClaimed = false;
    }

    public synchronized boolean cancel(ClientViewMessage.TravelCancel value) {
        if (begin == null || !begin.token().equals(value.token()) || begin.generation() != value.generation()) {
            return false;
        }
        clear();
        return true;
    }

    public synchronized Optional<ClientViewMessage.TravelCommit> commit(Commit request) {
        Objects.requireNonNull(request);
        if (begin == null || worldInvalidated || request.nowMillis() >= deadline
            || pendingCross != null && request.nowMillis() >= crossDeadline || readyRevision <= 0L
            || !begin.sourcePortal().equals(request.portal()) || !begin.sourceWorld().equals(request.sourceWorld())
            || !begin.world().dimension().equals(request.destinationWorld()) || !covered(request.arrival())) {
            return Optional.empty();
        }
        ClientViewMessage.TravelCommit result = new ClientViewMessage.TravelCommit(begin.token(), begin.generation(),
            pendingCross == null ? readyRevision : pendingCross.contentRevision(), request.sourceWorld(), request.destinationWorld(),
            request.arrival(), request.velocity());
        clear();
        return Optional.of(result);
    }

    public synchronized Optional<ClientViewMessage.TravelCancel> cancel() {
        if (begin == null) {
            return Optional.empty();
        }
        ClientViewMessage.TravelCancel result = new ClientViewMessage.TravelCancel(begin.token(), begin.generation());
        clear();
        return Optional.of(result);
    }

    private boolean acknowledged(ClientViewMessage.TravelCross value) {
        return begin != null && !worldInvalidated && begin.token().equals(value.token()) && begin.generation() == value.generation()
            && (acknowledgedBarriers.contains(value.contentRevision()) || pendingCross != null && pendingCross.equals(value));
    }

    private void releaseProbe(ClientViewMessage.TravelCached value) {
        byte[] hash = value.hash();
        for (Iterator<Probe> iterator = pendingProbes.iterator(); iterator.hasNext();) {
            ClientViewMessage.TravelReuse offer = iterator.next().offer();
            if (offer.token().equals(value.token()) && offer.generation() == value.generation()
                && offer.chunkX() == value.chunkX() && offer.chunkZ() == value.chunkZ() && offer.revision() == value.revision()
                && Arrays.equals(offer.hash(), hash)) {
                iterator.remove();
                return;
            }
        }
    }

    private static boolean sameSurface(ApertureDescriptor first, ApertureDescriptor second) {
        return second != null && !second.mirror() && first.originX() == second.originX() && first.originY() == second.originY()
            && first.originZ() == second.originZ() && first.facing() == second.facing() && first.quarterTurns() == second.quarterTurns()
            && first.kind() == second.kind() && first.apertureWidth() == second.apertureWidth()
            && first.apertureHeight() == second.apertureHeight() && first.targetIdentity() == second.targetIdentity()
            && Arrays.equals(first.apertureMask(), second.apertureMask());
    }

    private boolean complete() {
        if (columns.size() != begin.chunks().size()) {
            return false;
        }
        for (Column column : columns.values()) {
            if (!column.valid || !column.sent()) {
                return false;
            }
        }
        return true;
    }

    private boolean covered(ClientViewMessage.TravelPose pose) {
        int x = (int) Math.floor(pose.x()) >> 4;
        int z = (int) Math.floor(pose.z()) >> 4;
        if (pose.y() < begin.world().minY() || pose.y() >= begin.world().minY() + begin.world().height()) {
            return false;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!columns.containsKey(new ClientViewMessage.TravelCoordinate(x + dx, z + dz))) {
                    return false;
                }
            }
        }
        return true;
    }

    private void clear() {
        if (changes != null) {
            changes.removeListener(this);
            changes = null;
        }
        destinationWorld = null;
        worldInvalidated = false;
        automaticDeadline = 0L;
        columns.clear();
        emittedBarriers.clear();
        acknowledgedBarriers.clear();
        pendingCross = null;
        crossDeadline = 0L;
        crossClaimed = false;
        begin = null;
        bytes = 0;
        cursor = 0;
        captureCursor = 0;
        announced = false;
        reuseSelected = false;
        contentRevision = 0L;
        sentRevision = 0L;
        readyRevision = 0L;
    }

    public record Commit(UUID portal, String sourceWorld, String destinationWorld,
                         ClientViewMessage.TravelPose arrival, Vec3d velocity, long nowMillis) {
        public Commit {
            Objects.requireNonNull(portal);
            Objects.requireNonNull(sourceWorld);
            Objects.requireNonNull(destinationWorld);
            Objects.requireNonNull(arrival);
            Objects.requireNonNull(velocity);
        }
    }

    public record Authority(String world, ApertureDescriptor geometry, ClientViewMessage.TravelPose pose,
                            Vec3d velocity, double eyeHeight) {
        public Authority {
            Objects.requireNonNull(world);
            Objects.requireNonNull(geometry);
            Objects.requireNonNull(pose);
            Objects.requireNonNull(velocity);
            if (!Double.isFinite(eyeHeight) || eyeHeight <= 0.0D || eyeHeight > 4.0D
                || !Double.isFinite(velocity.x()) || !Double.isFinite(velocity.y()) || !Double.isFinite(velocity.z())) {
                throw new IllegalArgumentException("Travel authority");
            }
        }
    }

    private record SnapshotKey(UUID world, ClientViewMessage.TravelWorld metadata, ClientViewMessage.TravelCoordinate coordinate) {
    }

    private record Probe(ClientViewMessage.TravelReuse offer, long sentMillis) {
    }

    private static final class Payload {
        private final byte[] bytes;
        private byte[] hash;

        private Payload(byte[] bytes) {
            this.bytes = bytes;
        }

        private byte[] hash() {
            if (hash == null) {
                hash = ClientTravelHash.of(bytes);
            }
            return hash;
        }
    }

    private static final class Column {
        private final int revision;
        private final byte[] payload;
        private int fragment;
        private final Payload snapshot;
        private boolean probed;
        private long probedAt;
        private boolean cacheAnswered;
        private boolean reused;
        private boolean valid = true;

        private Column(int revision, Payload snapshot) {
            this.revision = revision;
            this.snapshot = snapshot;
            this.payload = snapshot.bytes;
        }

        private byte[] hash() {
            return snapshot.hash();
        }

        private boolean sent() {
            return reused || fragment * ViewStreamLimits.TRAVEL_FRAGMENT_BYTES >= payload.length;
        }
    }
}
