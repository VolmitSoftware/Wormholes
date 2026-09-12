package art.arcane.wormholes.render.atmosphere;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.render.ProjectionCellKey;

final class BiomeClaimRestoreTest {
    private static final UUID PORTAL_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID PORTAL_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final int LOCAL = 1;
    private static final int DESTINATION = 7;
    private static final int OTHER = 9;

    @Test
    void releaseRestoresEveryRetintedSectionExactlyOnce() {
        BiomeClaimSet set = new BiomeClaimSet(-64, 320, (x, y, z) -> LOCAL);
        Long2IntOpenHashMap overrides = new Long2IntOpenHashMap();
        overrides.put(quart(0, 64, 0), DESTINATION);
        overrides.put(quart(4, 68, 8), DESTINATION);
        overrides.put(quart(0, 80, 0), DESTINATION);

        List<BiomeClaimSet.ChunkBiomes> applied = set.apply(PORTAL_A, overrides);
        assertEquals(1, applied.size());
        BiomeClaimSet.ChunkBiomes chunk = applied.get(0);
        assertEquals(0, chunk.chunkX());
        assertEquals(0, chunk.chunkZ());
        assertEquals(24, chunk.sections().length);
        assertEquals(DESTINATION, chunk.sections()[8][cellIndex(0, 0, 0)]);
        assertEquals(DESTINATION, chunk.sections()[8][cellIndex(1, 1, 2)]);
        assertEquals(DESTINATION, chunk.sections()[9][cellIndex(0, 0, 0)]);
        assertEquals(LOCAL, chunk.sections()[8][cellIndex(2, 2, 2)], "untouched quart cells keep the local biome");
        assertEquals(LOCAL, chunk.sections()[0][0]);
        assertFalse(set.isEmpty());

        List<BiomeClaimSet.ChunkBiomes> restored = set.release(PORTAL_A);
        assertEquals(1, restored.size());
        int[][] sections = restored.get(0).sections();
        for (int[] section : sections) {
            for (int id : section) {
                assertEquals(LOCAL, id, "restore sends the local biome for every quart cell");
            }
        }
        assertTrue(set.isEmpty());
        assertTrue(set.release(PORTAL_A).isEmpty(), "a second release has nothing left to restore");
    }

    @Test
    void anUnchangedApplySendsNothingAndAnOverlappingPortalKeepsItsCells() {
        BiomeClaimSet set = new BiomeClaimSet(-64, 320, (x, y, z) -> LOCAL);
        Long2IntOpenHashMap first = new Long2IntOpenHashMap();
        first.put(quart(0, 64, 0), DESTINATION);
        Long2IntOpenHashMap second = new Long2IntOpenHashMap();
        second.put(quart(0, 64, 0), OTHER);
        second.put(quart(16, 64, 0), OTHER);

        assertEquals(1, set.apply(PORTAL_A, first).size());
        assertTrue(set.apply(PORTAL_A, first).isEmpty(), "re-applying the same overrides resends nothing");
        List<BiomeClaimSet.ChunkBiomes> merged = set.apply(PORTAL_B, second);
        assertEquals(1, merged.size(), "the contested cell keeps portal A; only the new chunk is sent");
        assertEquals(1, merged.get(0).chunkX());

        List<BiomeClaimSet.ChunkBiomes> afterRelease = set.release(PORTAL_A);
        assertEquals(1, afterRelease.size());
        assertEquals(OTHER, afterRelease.get(0).sections()[8][cellIndex(0, 0, 0)], "portal B takes over the shared cell");
        assertEquals(2, set.release(PORTAL_B).size(), "releasing the last portal restores both chunks it touched");
        assertTrue(set.isEmpty());
    }

    private static long quart(int x, int y, int z) {
        return ProjectionCellKey.pack(x >> 2, y >> 2, z >> 2);
    }

    private static int cellIndex(int qx, int qy, int qz) {
        return (qy << 4) | (qz << 2) | qx;
    }
}
