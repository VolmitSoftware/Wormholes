package art.arcane.wormholes.render.atmosphere;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import art.arcane.wormholes.render.ProjectionCellKey;

/**
 * Per-observer record of every quart cell whose biome was replaced, which portal owns the override,
 * and the local biomes needed to restore each chunk column exactly. The first portal to claim a quart
 * cell keeps it until it releases; every apply or release returns the full chunk columns that must be
 * resent so a restore never leaves a stale retint behind.
 */
public final class BiomeClaimSet {
    @FunctionalInterface
    public interface LocalBiomeSource {
        int biomeId(int x, int y, int z);
    }

    public record ChunkBiomes(int chunkX, int chunkZ, int[][] sections) {
    }

    private final int minHeight;
    private final int sectionCount;
    private final LocalBiomeSource local;
    private final Map<UUID, Long2IntOpenHashMap> portals;
    private final Long2ObjectOpenHashMap<UUID> owners;
    private final Long2IntOpenHashMap sent;
    private final Long2ObjectOpenHashMap<int[]> localColumns;

    public BiomeClaimSet(int minHeight, int maxHeight, LocalBiomeSource local) {
        this.minHeight = minHeight;
        this.sectionCount = Math.max(1, (maxHeight - minHeight) >> 4);
        this.local = local;
        this.portals = new HashMap<UUID, Long2IntOpenHashMap>(4);
        this.owners = new Long2ObjectOpenHashMap<UUID>(256);
        this.sent = new Long2IntOpenHashMap(256);
        this.sent.defaultReturnValue(-1);
        this.localColumns = new Long2ObjectOpenHashMap<int[]>(8);
    }

    public boolean isEmpty() {
        return sent.isEmpty();
    }

    public int retintedCells() {
        return sent.size();
    }

    public List<ChunkBiomes> apply(UUID portalId, Long2IntMap overrides) {
        Long2IntOpenHashMap copy = new Long2IntOpenHashMap(overrides == null ? 0 : overrides.size());
        copy.defaultReturnValue(-1);
        if (overrides != null) {
            copy.putAll(overrides);
        }
        Long2IntOpenHashMap previous = copy.isEmpty() ? portals.remove(portalId) : portals.put(portalId, copy);
        LongOpenHashSet dirtyChunks = new LongOpenHashSet();
        if (previous != null) {
            LongIterator released = previous.keySet().iterator();
            while (released.hasNext()) {
                long cell = released.nextLong();
                if (copy.containsKey(cell)) {
                    continue;
                }
                dropOwnership(portalId, cell, dirtyChunks);
            }
        }
        LongIterator added = copy.keySet().iterator();
        while (added.hasNext()) {
            long cell = added.nextLong();
            UUID owner = owners.get(cell);
            if (owner != null && !owner.equals(portalId)) {
                continue;
            }
            int biomeId = copy.get(cell);
            if (owner == null || sent.get(cell) != biomeId) {
                owners.put(cell, portalId);
                sent.put(cell, biomeId);
                dirtyChunks.add(chunkKeyOf(cell));
            }
        }
        return rebuild(dirtyChunks);
    }

    public List<ChunkBiomes> release(UUID portalId) {
        Long2IntOpenHashMap removed = portals.remove(portalId);
        if (removed == null) {
            return List.of();
        }
        LongOpenHashSet dirtyChunks = new LongOpenHashSet();
        LongIterator iterator = removed.keySet().iterator();
        while (iterator.hasNext()) {
            dropOwnership(portalId, iterator.nextLong(), dirtyChunks);
        }
        return rebuild(dirtyChunks);
    }

    public void clear() {
        portals.clear();
        owners.clear();
        sent.clear();
        localColumns.clear();
    }

    private void dropOwnership(UUID portalId, long cell, LongOpenHashSet dirtyChunks) {
        UUID owner = owners.get(cell);
        if (owner == null || !owner.equals(portalId)) {
            return;
        }
        for (Map.Entry<UUID, Long2IntOpenHashMap> other : portals.entrySet()) {
            if (other.getKey().equals(portalId) || !other.getValue().containsKey(cell)) {
                continue;
            }
            int biomeId = other.getValue().get(cell);
            owners.put(cell, other.getKey());
            if (sent.get(cell) != biomeId) {
                sent.put(cell, biomeId);
                dirtyChunks.add(chunkKeyOf(cell));
            }
            return;
        }
        owners.remove(cell);
        sent.remove(cell);
        dirtyChunks.add(chunkKeyOf(cell));
    }

    private List<ChunkBiomes> rebuild(LongOpenHashSet dirtyChunks) {
        if (dirtyChunks.isEmpty()) {
            return List.of();
        }
        List<ChunkBiomes> chunks = new ArrayList<ChunkBiomes>(dirtyChunks.size());
        LongIterator iterator = dirtyChunks.iterator();
        while (iterator.hasNext()) {
            long chunkKey = iterator.nextLong();
            int chunkX = (int) (chunkKey >> 32);
            int chunkZ = (int) chunkKey;
            chunks.add(build(chunkX, chunkZ));
        }
        return chunks;
    }

    private ChunkBiomes build(int chunkX, int chunkZ) {
        int[] column = localColumn(chunkX, chunkZ);
        int[][] sections = new int[sectionCount][AtmosphereDominance.QUART_CELLS_PER_SECTION];
        for (int section = 0; section < sectionCount; section++) {
            System.arraycopy(column, section * AtmosphereDominance.QUART_CELLS_PER_SECTION, sections[section], 0,
                AtmosphereDominance.QUART_CELLS_PER_SECTION);
        }
        int quartMinY = minHeight >> 2;
        LongIterator iterator = sent.keySet().iterator();
        while (iterator.hasNext()) {
            long cell = iterator.nextLong();
            int qx = ProjectionCellKey.unpackX(cell);
            int qy = ProjectionCellKey.unpackY(cell);
            int qz = ProjectionCellKey.unpackZ(cell);
            if ((qx >> 2) != chunkX || (qz >> 2) != chunkZ) {
                continue;
            }
            int section = (qy - quartMinY) >> 2;
            if (section < 0 || section >= sectionCount) {
                continue;
            }
            sections[section][cellIndex(qx, qy, qz)] = sent.get(cell);
        }
        return new ChunkBiomes(chunkX, chunkZ, sections);
    }

    private int[] localColumn(int chunkX, int chunkZ) {
        long chunkKey = (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
        int[] cached = localColumns.get(chunkKey);
        if (cached != null) {
            return cached;
        }
        int[] column = new int[sectionCount * AtmosphereDominance.QUART_CELLS_PER_SECTION];
        int index = 0;
        for (int section = 0; section < sectionCount; section++) {
            int sectionMinY = minHeight + (section << 4);
            for (int qy = 0; qy < 4; qy++) {
                for (int qz = 0; qz < 4; qz++) {
                    for (int qx = 0; qx < 4; qx++) {
                        column[index++] = Math.max(0, local.biomeId(
                            (chunkX << 4) + (qx << 2), sectionMinY + (qy << 2), (chunkZ << 4) + (qz << 2)));
                    }
                }
            }
        }
        localColumns.put(chunkKey, column);
        return column;
    }

    static long chunkKeyOf(long quartCell) {
        int chunkX = ProjectionCellKey.unpackX(quartCell) >> 2;
        int chunkZ = ProjectionCellKey.unpackZ(quartCell) >> 2;
        return (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    static int cellIndex(int qx, int qy, int qz) {
        return ((qy & 3) << 4) | ((qz & 3) << 2) | (qx & 3);
    }
}
