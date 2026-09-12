package art.arcane.wormholes.render.plate;

import java.util.UUID;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;

/**
 * Immutable, versioned result of one portal-scoped build. Cells are keyed by local cell key; the
 * map is frozen after construction and shared read-only between every observer of the portal.
 */
public final class ViewPlate {
    private static final long BASE_BYTES = 256L;

    private final ViewPlateKey key;
    private final Long2ObjectOpenHashMap<PlateCell> cells;
    private final long destinationRevision;
    private final long transformRevision;
    private final UUID destinationWorldId;
    private final long trackerVersion;
    private final int minChunkX;
    private final int minChunkZ;
    private final int maxChunkX;
    private final int maxChunkZ;
    private final long bytes;
    private volatile long lastUsedNanos;

    public ViewPlate(ViewPlateKey key,
                     Long2ObjectOpenHashMap<PlateCell> cells,
                     long destinationRevision,
                     long transformRevision,
                     UUID destinationWorldId,
                     long trackerVersion,
                     int minChunkX,
                     int minChunkZ,
                     int maxChunkX,
                     int maxChunkZ,
                     long bytes) {
        this.key = key;
        this.cells = cells;
        this.destinationRevision = destinationRevision;
        this.transformRevision = transformRevision;
        this.destinationWorldId = destinationWorldId;
        this.trackerVersion = trackerVersion;
        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.maxChunkX = maxChunkX;
        this.maxChunkZ = maxChunkZ;
        this.bytes = Math.max(BASE_BYTES, bytes);
        this.lastUsedNanos = System.nanoTime();
    }

    static long estimateBytes(Long2ObjectOpenHashMap<PlateCell> cells) {
        long total = BASE_BYTES + ((long) cells.size() * 24L);
        for (PlateCell cell : cells.values()) {
            total += cell.bytes();
        }
        return total;
    }

    public ViewPlateKey key() {
        return key;
    }

    public PlateCell cell(long localKey) {
        return cells.get(localKey);
    }

    public LongSet cellKeys() {
        return cells.keySet();
    }

    public int cellCount() {
        return cells.size();
    }

    public boolean isEmpty() {
        return cells.isEmpty();
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
}
