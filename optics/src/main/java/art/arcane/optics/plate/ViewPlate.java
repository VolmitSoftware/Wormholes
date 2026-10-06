package art.arcane.optics.plate;

import art.arcane.optics.math.CellKeys;

import art.arcane.optics.math.BlockBox;

import java.util.UUID;

import art.arcane.optics.view.WorldChangeTracker;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;

/**
 * Immutable, versioned result of one portal-scoped build. Cells are addressed by local cell key; the
 * grid is frozen after construction and shared read-only between every observer of the portal. Chunks
 * that changed after the build are recorded as dirty so observers sample those cells live until a
 * patched plate replaces this one.
 */
public final class ViewPlate<B> {
    static final int DIRTY_MARGIN = 2;
    private static final long BASE_BYTES = 256L;

    private final ViewPlateKey key;
    private final PlateGrid<B> grid;
    private final long destinationRevision;
    private final long transformRevision;
    private final UUID destinationWorldId;
    private final long trackerVersion;
    private final int minChunkX;
    private final int minChunkZ;
    private final int maxChunkX;
    private final int maxChunkZ;
    private final long bytes;
    private final PlateEnvironment environment;
    private final long builtNanos;
    private volatile long lastUsedNanos;
    private volatile long dirtCheckedVersion;
    private volatile LongOpenHashSet dirtyChunks;

    public ViewPlate(ViewPlateKey key,
                     PlateGrid<B> grid,
                     long destinationRevision,
                     long transformRevision,
                     UUID destinationWorldId,
                     long trackerVersion,
                     int minChunkX,
                     int minChunkZ,
                     int maxChunkX,
                     int maxChunkZ,
                     long bytes, PlateEnvironment environment) {
        this.key = key;
        this.grid = grid;
        this.destinationRevision = destinationRevision;
        this.transformRevision = transformRevision;
        this.destinationWorldId = destinationWorldId;
        this.trackerVersion = trackerVersion;
        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.maxChunkX = maxChunkX;
        this.maxChunkZ = maxChunkZ;
        this.bytes = Math.max(BASE_BYTES, bytes) + (environment == null ? 0 : environment.bytes());
        this.environment = environment;
        this.builtNanos = System.nanoTime();
        this.lastUsedNanos = builtNanos;
        this.dirtCheckedVersion = trackerVersion;
    }

    public static long predictBytes(BlockBox box) {
        return BASE_BYTES + PlateGrid.predictBytes(box);
    }

    public static <B> long estimateBytes(PlateGrid<B> grid) {
        return BASE_BYTES + grid.bytes();
    }

    public PlateEnvironment environment() {
        return environment;
    }

    public ViewPlateKey key() {
        return key;
    }

    public BlockBox box() {
        return grid.box();
    }

    public PlateCell<B> cell(long localKey) {
        return grid.cell(localKey);
    }

    public int cellRef(int x, int y, int z) {
        return grid.ref(x, y, z);
    }

    public PlateCell<B> paletteCell(int ref) {
        return grid.paletteCell(ref);
    }

    public int paletteSize() {
        return grid.paletteSize();
    }

    public PlateCell<B> cleanCell(long localKey, int remoteX, int remoteZ) {
        LongOpenHashSet dirty = dirtyChunks;
        if (dirty != null && touches(dirty, remoteX, remoteZ)) {
            return null;
        }
        return grid.cell(localKey);
    }

    public boolean dirty() {
        return dirtyChunks != null;
    }

    public ChangeRegion changeRegion() {
        return new ChangeRegion(destinationWorldId, trackerVersion, minChunkX, minChunkZ, maxChunkX, maxChunkZ);
    }

    public LongSet dirtyChunks() {
        LongOpenHashSet dirty = dirtyChunks;
        return dirty == null ? LongSets.EMPTY_SET : LongSets.unmodifiable(dirty);
    }

    public boolean builtBefore(long nanos) {
        return builtNanos - nanos < 0L;
    }

    public boolean collectDirt(WorldChangeTracker tracker, LongCollection out) {
        LongOpenHashSet dirty = dirtyChunks;
        if (dirty != null) {
            out.addAll(dirty);
        }
        return collectTracked(tracker, out);
    }

    public boolean refreshDirt(WorldChangeTracker tracker) {
        long version = tracker.currentVersion();
        if (version == dirtCheckedVersion) {
            return true;
        }
        LongArrayList changed = new LongArrayList();
        if (!collectTracked(tracker, changed)) {
            return false;
        }
        if (!changed.isEmpty()) {
            markDirty(changed);
        }
        dirtCheckedVersion = version;
        return true;
    }

    public LongSet cellKeys() {
        return grid.cellKeys();
    }

    public int cellCount() {
        return grid.cellCount();
    }

    public boolean isEmpty() {
        return grid.cellCount() == 0;
    }

    public long destinationRevision() {
        return destinationRevision;
    }

    public long transformRevision() {
        return transformRevision;
    }

    public UUID destinationWorldId() {
        return destinationWorldId;
    }

    public long trackerVersion() {
        return trackerVersion;
    }

    public int minChunkX() {
        return minChunkX;
    }

    public int minChunkZ() {
        return minChunkZ;
    }

    public int maxChunkX() {
        return maxChunkX;
    }

    public int maxChunkZ() {
        return maxChunkZ;
    }

    public long bytes() {
        return bytes;
    }

    long lastUsedNanos() {
        return lastUsedNanos;
    }

    void touch(long nowNanos) {
        lastUsedNanos = nowNanos;
    }

    boolean matches(long expectedDestinationRevision, long expectedTransformRevision) {
        return destinationRevision == expectedDestinationRevision && transformRevision == expectedTransformRevision;
    }

    PlateGrid<B> grid() {
        return grid;
    }

    synchronized void markDirty(LongCollection chunks) {
        LongOpenHashSet current = dirtyChunks;
        if (current != null && current.containsAll(chunks)) {
            return;
        }
        LongOpenHashSet merged = current == null ? new LongOpenHashSet(chunks) : new LongOpenHashSet(current);
        merged.addAll(chunks);
        dirtyChunks = merged;
    }

    private boolean collectTracked(WorldChangeTracker tracker, LongCollection out) {
        if (destinationWorldId == null || trackerVersion == Long.MIN_VALUE || minChunkX > maxChunkX) {
            return true;
        }
        return tracker.collectDirtySince(destinationWorldId, minChunkX - 1, minChunkZ - 1, maxChunkX + 1, maxChunkZ + 1,
            trackerVersion, out);
    }

    static boolean touches(LongSet dirty, int remoteX, int remoteZ) {
        int minChunkX = (remoteX - DIRTY_MARGIN) >> 4;
        int maxChunkX = (remoteX + DIRTY_MARGIN) >> 4;
        int minChunkZ = (remoteZ - DIRTY_MARGIN) >> 4;
        int maxChunkZ = (remoteZ + DIRTY_MARGIN) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (dirty.contains(CellKeys.chunkKey(chunkX, chunkZ))) {
                    return true;
                }
            }
        }
        return false;
    }

    public record ChangeRegion(UUID worldId, long version, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        public boolean dirty(WorldChangeTracker tracker) {
            return worldId != null && version != Long.MIN_VALUE && minChunkX <= maxChunkX
                && tracker.dirtySince(worldId, minChunkX - 1, minChunkZ - 1, maxChunkX + 1, maxChunkZ + 1, version);
        }
    }
}
