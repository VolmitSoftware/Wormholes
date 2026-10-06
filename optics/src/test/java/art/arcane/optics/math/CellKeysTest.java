package art.arcane.optics.math;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

final class CellKeysTest {
    private static final int[] EDGES = {0, 1, -1, 15, -16, 1_875_000, -1_875_000, Integer.MAX_VALUE, Integer.MIN_VALUE};

    @Test
    void chunkKeyMatchesTheInlineColumnPacking() {
        SplittableRandom random = new SplittableRandom(0x5EEDL);
        for (int index = 0; index < 10_000; index++) {
            int chunkX = random.nextInt();
            int chunkZ = random.nextInt();
            assertEquals(inlineChunkKey(chunkX, chunkZ), CellKeys.chunkKey(chunkX, chunkZ));
        }
        for (int chunkX : EDGES) {
            for (int chunkZ : EDGES) {
                assertEquals(inlineChunkKey(chunkX, chunkZ), CellKeys.chunkKey(chunkX, chunkZ));
            }
        }
    }

    @Test
    void chunkKeyUnpacksWithTheInlineShifts() {
        for (int chunkX : EDGES) {
            for (int chunkZ : EDGES) {
                long key = CellKeys.chunkKey(chunkX, chunkZ);
                assertEquals(chunkX, (int) (key >> 32));
                assertEquals(chunkZ, (int) key);
            }
        }
    }

    @Test
    void chunkKeyOfBlockShiftsMatchTheLightingAndScanCopies() {
        SplittableRandom random = new SplittableRandom(42L);
        for (int index = 0; index < 10_000; index++) {
            int x = random.nextInt(-30_000_000, 30_000_000);
            int z = random.nextInt(-30_000_000, 30_000_000);
            long scanCopy = ((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
            long lightingCopy = (((long) (x >> 4)) << 32) | (((long) (z >> 4)) & 0xFFFFFFFFL);
            assertEquals(scanCopy, CellKeys.chunkKey(x >> 4, z >> 4));
            assertEquals(lightingCopy, CellKeys.chunkKey(x >> 4, z >> 4));
        }
    }

    @Test
    void sectionKeyMatchesTheCellPackingOfSectionCoordinates() {
        SplittableRandom random = new SplittableRandom(7L);
        for (int index = 0; index < 10_000; index++) {
            int x = random.nextInt(-30_000_000, 30_000_000);
            int y = random.nextInt(-2048, 2048);
            int z = random.nextInt(-30_000_000, 30_000_000);
            long key = CellKeys.sectionKey(x >> 4, y >> 4, z >> 4);
            assertEquals(CellKeys.pack(x >> 4, y >> 4, z >> 4), key);
            assertEquals(x >> 4, CellKeys.unpackX(key));
            assertEquals(y >> 4, CellKeys.unpackY(key));
            assertEquals(z >> 4, CellKeys.unpackZ(key));
        }
    }

    private static long inlineChunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
