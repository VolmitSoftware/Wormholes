package art.arcane.wormholes.render.atmosphere;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;

import art.arcane.wormholes.render.ProjectedBlockClaim;
import art.arcane.wormholes.render.ProjectionCellKey;

/**
 * Section selection for biome retints. Biomes are stored per 4x4x4 quart cell, 64 per chunk section;
 * a section is dominated when the claimed cells cover at least the configured fraction of its 64 quart
 * cells. Only dominated sections are retinted, and only the quart cells the view actually covers.
 */
public final class AtmosphereDominance {
    public static final int QUART_CELLS_PER_SECTION = 64;

    @FunctionalInterface
    public interface RemoteBiomeLookup {
        /** Client biome id for the destination cell a claim was sampled from, or -1 when unknown. */
        int biomeIdFor(long remoteKey);
    }

    private AtmosphereDominance() {
    }

    /** Returns quart-cell key (quart coordinates packed with {@link ProjectionCellKey#pack}) to biome id. */
    public static Long2IntOpenHashMap compute(Long2ObjectMap<ProjectedBlockClaim> claims,
                                              double dominance,
                                              RemoteBiomeLookup lookup) {
        Long2IntOpenHashMap out = new Long2IntOpenHashMap();
        out.defaultReturnValue(-1);
        if (claims == null || claims.isEmpty()) {
            return out;
        }
        Long2ObjectOpenHashMap<Long2LongOpenHashMap> sections = new Long2ObjectOpenHashMap<Long2LongOpenHashMap>(8);
        for (Long2ObjectMap.Entry<ProjectedBlockClaim> entry : claims.long2ObjectEntrySet()) {
            ProjectedBlockClaim claim = entry.getValue();
            if (claim == null || claim.getLightRemoteKey() == ProjectedBlockClaim.NO_REMOTE_KEY) {
                continue;
            }
            long localKey = entry.getLongKey();
            int x = ProjectionCellKey.unpackX(localKey);
            int y = ProjectionCellKey.unpackY(localKey);
            int z = ProjectionCellKey.unpackZ(localKey);
            long sectionKey = ProjectionCellKey.pack(x >> 4, y >> 4, z >> 4);
            long quartKey = ProjectionCellKey.pack(x >> 2, y >> 2, z >> 2);
            Long2LongOpenHashMap cells = sections.get(sectionKey);
            if (cells == null) {
                cells = new Long2LongOpenHashMap(QUART_CELLS_PER_SECTION);
                sections.put(sectionKey, cells);
            }
            cells.putIfAbsent(quartKey, claim.getLightRemoteKey());
        }
        double threshold = Math.max(0.0D, Math.min(1.0D, dominance)) * QUART_CELLS_PER_SECTION;
        for (Long2LongOpenHashMap cells : sections.values()) {
            if (cells.size() < threshold) {
                continue;
            }
            LongIterator iterator = cells.keySet().iterator();
            while (iterator.hasNext()) {
                long quartKey = iterator.nextLong();
                int biomeId = lookup.biomeIdFor(cells.get(quartKey));
                if (biomeId >= 0) {
                    out.put(quartKey, biomeId);
                }
            }
        }
        return out;
    }
}
