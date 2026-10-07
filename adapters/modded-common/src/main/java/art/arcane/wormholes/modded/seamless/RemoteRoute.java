package art.arcane.wormholes.modded.seamless;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.plate.ChunkLease;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.ServerLevel;

import java.util.Objects;
import java.util.UUID;

public final class RemoteRoute {
    private static final int DIRTY_VIEWER_INTERVAL_TICKS = 2;

    private final UUID playerId;
    private final UUID sourceId;
    private final UUID destinationId;
    private final ServerLevel level;
    private final Vec3d anchor;
    private final RouteStream stream;
    private final IntOpenHashSet paired = new IntOpenHashSet();
    private final Long2ObjectOpenHashMap<ChunkLease> leases = new Long2ObjectOpenHashMap<>();
    private int handle;
    private int sequence;
    private boolean opened;
    private boolean closed;
    private boolean viewersDirty = true;
    private long viewersTick = Long.MIN_VALUE / 2;
    private long lingerUntil = Long.MAX_VALUE;
    private long changesVersion;
    private RemoteViewerConnection viewer;

    public RemoteRoute(Key key, ServerLevel level, Vec3d anchor, RouteWindow window, int handle) {
        Objects.requireNonNull(key, "key");
        this.playerId = key.player();
        this.sourceId = key.source();
        this.destinationId = key.destination();
        this.level = level;
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        this.stream = new RouteStream(window);
        this.handle = handle;
    }

    public UUID playerId() {
        return playerId;
    }

    public UUID sourceId() {
        return sourceId;
    }

    public UUID destinationId() {
        return destinationId;
    }

    public Key key() {
        return new Key(playerId, sourceId, destinationId);
    }

    public ServerLevel level() {
        return level;
    }

    public Vec3d anchor() {
        return anchor;
    }

    public RouteStream stream() {
        return stream;
    }

    public RouteWindow window() {
        return stream.window();
    }

    public int handle() {
        return handle;
    }

    public void handle(int next) {
        handle = next;
    }

    public boolean resident() {
        return handle > 0;
    }

    public int nextSequence() {
        int current = sequence;
        sequence = (sequence + 1) & Integer.MAX_VALUE;
        return current;
    }

    public int lastSequence() {
        return (sequence - 1) & Integer.MAX_VALUE;
    }

    public boolean opened() {
        return opened;
    }

    public void opened(boolean value) {
        opened = value;
    }

    public boolean closed() {
        return closed;
    }

    public void close() {
        closed = true;
        for (ChunkLease lease : leases.values()) {
            lease.close();
        }
        leases.clear();
        paired.clear();
        stream.clear();
    }

    public IntOpenHashSet paired() {
        return paired;
    }

    public Long2ObjectOpenHashMap<ChunkLease> leases() {
        return leases;
    }

    public boolean viewersDue(long tick, int interval) {
        return tick - viewersTick >= (viewersDirty ? DIRTY_VIEWER_INTERVAL_TICKS : interval);
    }

    public void viewersEvaluated(long tick) {
        viewersDirty = false;
        viewersTick = tick;
    }

    public void viewersDirty() {
        viewersDirty = true;
    }

    public long lingerUntil() {
        return lingerUntil;
    }

    public void lingerUntil(long tick) {
        lingerUntil = tick;
    }

    public long changesVersion() {
        return changesVersion;
    }

    public void changesVersion(long version) {
        changesVersion = version;
    }

    public RemoteViewerConnection viewer() {
        return viewer;
    }

    public void viewer(RemoteViewerConnection connection) {
        viewer = connection;
    }

    public record Key(UUID player, UUID source, UUID destination) {
        public Key {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(destination, "destination");
        }
    }
}
