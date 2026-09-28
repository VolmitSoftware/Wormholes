package art.arcane.wormholes.nexus;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Chunk-bucketed index of portal control blocks, mirroring {@code door/DoorSpatialIndex}. Redstone
 * events are hot, so {@link #portalAt} probes with a reusable key and walks a short per-chunk list:
 * a miss costs one map lookup and allocates nothing.
 */
public final class RedstoneIoIndex {
    private final ConcurrentHashMap<ChunkKey, CopyOnWriteArrayList<Wired>> byChunk = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Wired> byPortal = new ConcurrentHashMap<>();
    private final ThreadLocal<ChunkProbe> probes = ThreadLocal.withInitial(ChunkProbe::new);

    public synchronized void put(UUID portalId, UUID worldId, int blockX, int blockY, int blockZ) {
        Objects.requireNonNull(portalId, "portalId");
        Objects.requireNonNull(worldId, "worldId");
        remove(portalId);
        Wired wired = new Wired(portalId, worldId, blockX, blockY, blockZ);
        byPortal.put(portalId, wired);
        byChunk.computeIfAbsent(wired.chunkKey(), ignored -> new CopyOnWriteArrayList<>()).add(wired);
    }

    public synchronized void remove(UUID portalId) {
        Wired removed = portalId == null ? null : byPortal.remove(portalId);
        if (removed == null) {
            return;
        }
        ChunkKey key = removed.chunkKey();
        CopyOnWriteArrayList<Wired> bucket = byChunk.get(key);
        if (bucket == null) {
            return;
        }
        bucket.remove(removed);
        if (bucket.isEmpty()) {
            byChunk.remove(key, bucket);
        }
    }

    /** The portal wired to this block, or null. Allocation-free. */
    public UUID portalAt(UUID worldId, int blockX, int blockY, int blockZ) {
        if (worldId == null) {
            return null;
        }
        ChunkProbe probe = probes.get();
        probe.move(worldId, Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16));
        List<Wired> bucket = byChunk.get(probe);
        if (bucket == null) {
            return null;
        }
        for (int index = 0; index < bucket.size(); index++) {
            Wired wired = bucket.get(index);
            if (wired.blockX == blockX && wired.blockY == blockY && wired.blockZ == blockZ
                    && wired.worldId.equals(worldId)) {
                return wired.portalId;
            }
        }
        return null;
    }

    public int size() {
        return byPortal.size();
    }

    public synchronized void clear() {
        byPortal.clear();
        byChunk.clear();
    }

    private static int chunkHash(UUID worldId, int chunkX, int chunkZ) {
        int result = worldId.hashCode();
        result = 31 * result + chunkX;
        return 31 * result + chunkZ;
    }

    private static final class Wired {
        private final UUID portalId;
        private final UUID worldId;
        private final int blockX;
        private final int blockY;
        private final int blockZ;

        private Wired(UUID portalId, UUID worldId, int blockX, int blockY, int blockZ) {
            this.portalId = portalId;
            this.worldId = worldId;
            this.blockX = blockX;
            this.blockY = blockY;
            this.blockZ = blockZ;
        }

        private ChunkKey chunkKey() {
            return new ChunkKey(worldId, Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16));
        }
    }

    private record ChunkKey(UUID worldId, int chunkX, int chunkZ) {
        @Override
        public int hashCode() {
            return chunkHash(worldId, chunkX, chunkZ);
        }
    }

    /** Reusable lookup key so a probe never allocates. Equal to the {@link ChunkKey} it describes. */
    private static final class ChunkProbe {
        private UUID worldId;
        private int chunkX;
        private int chunkZ;

        private void move(UUID worldId, int chunkX, int chunkZ) {
            this.worldId = worldId;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        @Override
        public boolean equals(Object value) {
            return value instanceof ChunkKey key
                    && chunkX == key.chunkX()
                    && chunkZ == key.chunkZ()
                    && worldId.equals(key.worldId());
        }

        @Override
        public int hashCode() {
            return chunkHash(worldId, chunkX, chunkZ);
        }
    }
}
