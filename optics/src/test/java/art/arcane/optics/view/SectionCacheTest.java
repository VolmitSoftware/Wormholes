package art.arcane.optics.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.state.StateProperties;
import art.arcane.optics.scan.ProjectorSampleMemo;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

public final class SectionCacheTest {
    private static final Block AIR = new Block(Kind.AIR, 0);
    private static final Block CAVE_AIR = new Block(Kind.CAVE_AIR, 0);
    private static final Block STONE = new Block(Kind.STONE, 0);
    private static final Block GLASS = new Block(Kind.GLASS, 0);
    private static final int MIN_SECTION_Y = -2;
    private static final int MAX_SECTION_Y = 2;
    private static final SectionCache.Limits OPEN_LIMITS = new SectionCache.Limits(true, 1L << 30, 1024, 600);

    @Test
    public void paletteRoundTripKeepsEveryCellDataAndMaterial() {
        FakeWorld world = new FakeWorld();
        Random random = new Random(11L);
        Block[] choices = {AIR, CAVE_AIR, STONE, GLASS, new Block(Kind.STONE, 3), new Block(Kind.GLASS, 7)};
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    world.set(x, y, z, choices[random.nextInt(choices.length)]);
                }
            }
        }
        SectionCache<Block, Kind> cache = new SectionCache<Block, Kind>(new Blocks(), OPEN_LIMITS);
        SectionCache<Block, Kind>.WorldSections sections = cache.world(world, MIN_SECTION_Y, MAX_SECTION_Y);
        cache.tick(1);

        CachedSection<Block, Kind> section = sections.section(0, 0, 0);

        assertNotNull(section);
        assertEquals(6, section.paletteSize());
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    int index = CachedSection.index(x, y, z);
                    Block expected = world.get(x, y, z);
                    assertEquals(expected.kind == Kind.CAVE_AIR ? AIR : expected, section.data(index));
                    assertEquals(expected.kind, section.material(index));
                    assertEquals(expected.kind == Kind.STONE, section.occluding(index));
                }
            }
        }
    }

    @Test
    public void largePaletteAndUniformSectionsRoundTrip() {
        FakeWorld world = new FakeWorld();
        for (int index = 0; index < CachedSection.CELLS; index++) {
            world.set(index & 15, index >> 8, (index >> 4) & 15, new Block(Kind.STONE, index % 700));
        }
        for (int x = 16; x < 32; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    world.set(x, y, z, GLASS);
                }
            }
        }
        SectionCache<Block, Kind> cache = new SectionCache<Block, Kind>(new Blocks(), OPEN_LIMITS);
        SectionCache<Block, Kind>.WorldSections sections = cache.world(world, MIN_SECTION_Y, MAX_SECTION_Y);
        cache.tick(1);

        CachedSection<Block, Kind> mixed = sections.section(0, 0, 0);
        CachedSection<Block, Kind> uniform = sections.section(1, 0, 0);

        assertEquals(700, mixed.paletteSize());
        for (int index = 0; index < CachedSection.CELLS; index++) {
            assertEquals(new Block(Kind.STONE, index % 700), mixed.data(index));
        }
        assertEquals(1, uniform.paletteSize());
        assertSame(GLASS, uniform.data(CachedSection.index(4, 5, 6)));
        assertTrue(uniform.bytes() < mixed.bytes());
    }

    @Test
    public void buriedDepthMatchesTheReferenceForRandomShellsAcrossSectionBoundaries() {
        double[] densities = {0.55D, 0.8D, 0.92D, 0.97D};
        for (int seed = 0; seed < densities.length; seed++) {
            FakeWorld world = randomWorld(new Random(0xB00L + seed), densities[seed], -32, 47);
            SectionCache<Block, Kind> cache = new SectionCache<Block, Kind>(new Blocks(), OPEN_LIMITS);
            SectionCache<Block, Kind>.WorldSections sections = cache.world(world, MIN_SECTION_Y, MAX_SECTION_Y);
            cache.tick(1);
            int[] depthCounts = new int[3];
            for (int x = -16; x < 32; x++) {
                for (int y = -16; y < 32; y++) {
                    for (int z = -16; z < 32; z++) {
                        if (world.get(x, y, z).kind != Kind.STONE) {
                            continue;
                        }
                        int expected = referenceDepth(world, x, y, z);
                        assertEquals(expected, sections.buriedDepth(x, y, z),
                            "seed " + seed + " cell " + x + "," + y + "," + z);
                        depthCounts[expected]++;
                    }
                }
            }
            if (densities[seed] > 0.9D) {
                assertTrue(depthCounts[1] > 0 && depthCounts[2] > 0, "dense shells must exercise every depth");
            }
        }
    }

    @Test
    public void buriedDepthIsBitIdenticalToTheMemoProbe() {
        FakeWorld world = randomWorld(new Random(0x5EEDL), 0.9D, -32, 47);
        SectionCache<Block, Kind> cache = new SectionCache<Block, Kind>(new Blocks(), OPEN_LIMITS);
        SectionCache<Block, Kind>.WorldSections sections = cache.world(world, MIN_SECTION_Y, MAX_SECTION_Y);
        cache.tick(1);
        ProjectorSampleMemo<Block, Kind, FakeWorld> memo = new ProjectorSampleMemo<Block, Kind, FakeWorld>(new Blocks(), () -> null);

        for (int x = -16; x < 32; x++) {
            for (int y = -16; y < 32; y++) {
                for (int z = -16; z < 32; z++) {
                    Block block = world.get(x, y, z);
                    if (block.kind != Kind.STONE) {
                        continue;
                    }
                    assertEquals(memo.occlusionDepthInView(world, x, y, z, block), sections.buriedDepth(x, y, z),
                        "cell " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    public void edgeNeighbourOpeningDropsTheCornerCellToBackingDepth() {
        FakeWorld world = new FakeWorld();
        world.fill(-16, -16, -16, 31, 31, 31, STONE);
        world.set(16, 16, 15, AIR);
        SectionCache<Block, Kind> cache = new SectionCache<Block, Kind>(new Blocks(), OPEN_LIMITS);
        SectionCache<Block, Kind>.WorldSections sections = cache.world(world, MIN_SECTION_Y, MAX_SECTION_Y);
        cache.tick(1);

        assertEquals(1, sections.buriedDepth(15, 15, 15));
        assertEquals(2, sections.buriedDepth(15, 15, 14));
        assertEquals(2, sections.buriedDepth(14, 14, 14));
    }

    @Test
    public void cellsAtTheWorldCeilingAreExposed() {
        FakeWorld world = new FakeWorld();
        int top = (MAX_SECTION_Y << 4) + 15;
        world.fill(-16, top - 31, -16, 31, top, 31, STONE);
        SectionCache<Block, Kind> cache = new SectionCache<Block, Kind>(new Blocks(), OPEN_LIMITS);
        SectionCache<Block, Kind>.WorldSections sections = cache.world(world, MIN_SECTION_Y, MAX_SECTION_Y);
        cache.tick(1);

        assertEquals(0, sections.buriedDepth(5, top, 5));
        assertEquals(1, sections.buriedDepth(5, top - 1, 5));
        assertEquals(2, sections.buriedDepth(5, top - 2, 5));
    }

    @Test
    public void missingNeighbourColumnLeavesOnlyDependentCellsUnknownUntilItIsCaptured() {
        FakeWorld world = new FakeWorld();
        world.fill(-16, -16, -16, 31, 31, 31, STONE);
        world.unloaded.add(columnKey(1, 0));
        SectionCache<Block, Kind> cache = new SectionCache<Block, Kind>(new Blocks(), OPEN_LIMITS);
        SectionCache<Block, Kind>.WorldSections sections = cache.world(world, MIN_SECTION_Y, MAX_SECTION_Y);
        cache.tick(1);

        assertEquals(2, sections.buriedDepth(8, 8, 8));
        assertEquals(2, sections.buriedDepth(13, 8, 8));
        assertEquals(-1, sections.buriedDepth(14, 8, 8));
        assertEquals(-1, sections.buriedDepth(15, 8, 8));
        assertEquals(-1, sections.buriedDepth(15, 15, 15));
        assertNull(sections.section(1, 0, 0));

        world.unloaded.clear();
        cache.tick(2);
        assertNotNull(sections.section(1, 0, 0));
        assertEquals(2, sections.buriedDepth(15, 8, 8));
        assertEquals(2, sections.buriedDepth(15, 15, 15));
    }

    @Test
    public void partialHalosNeverAnswerWrong() {
        Random random = new Random(0xFACEL);
        for (int round = 0; round < 6; round++) {
            FakeWorld world = randomWorld(random, 0.93D, -32, 47);
            for (int chunkX = -2; chunkX <= 2; chunkX++) {
                for (int chunkZ = -2; chunkZ <= 2; chunkZ++) {
                    if ((chunkX != 0 || chunkZ != 0) && random.nextInt(3) == 0) {
                        world.unloaded.add(columnKey(chunkX, chunkZ));
                    }
                }
            }
            SectionCache<Block, Kind> cache = new SectionCache<Block, Kind>(new Blocks(), OPEN_LIMITS);
            SectionCache<Block, Kind>.WorldSections sections = cache.world(world, MIN_SECTION_Y, MAX_SECTION_Y);
            cache.tick(1);
            int known = 0;
            for (int x = 0; x < 16; x++) {
                for (int y = -16; y < 32; y++) {
                    for (int z = 0; z < 16; z++) {
                        if (world.get(x, y, z).kind != Kind.STONE) {
                            continue;
                        }
                        int depth = sections.buriedDepth(x, y, z);
                        if (depth < 0) {
                            continue;
                        }
                        known++;
                        assertEquals(referenceDepth(world, x, y, z), depth, "round " + round + " cell " + x + "," + y + "," + z);
                    }
                }
            }
            assertTrue(known > 0);
        }
    }

    @Test
    public void chunkBudgetDefersNewColumnsToLaterTicks() {
        FakeWorld world = new FakeWorld();
        world.fill(0, 0, 0, 47, 15, 15, GLASS);
        SectionCache<Block, Kind> cache = new SectionCache<Block, Kind>(new Blocks(),
            new SectionCache.Limits(true, 1L << 30, 2, 600));
        SectionCache<Block, Kind>.WorldSections sections = cache.world(world, MIN_SECTION_Y, MAX_SECTION_Y);
        cache.tick(1);

        assertNotNull(sections.section(0, 0, 0));
        assertNotNull(sections.section(0, 1, 0), "a column opened this tick fills more sections for free");
        assertNotNull(sections.section(1, 0, 0));
        assertNull(sections.section(2, 0, 0));

        cache.tick(2);
        assertNotNull(sections.section(2, 0, 0));
    }

    @Test
    public void arrivalCaptureIgnoresTheChunkBudgetAndServesAfterUnload() {
        FakeWorld world = new FakeWorld();
        world.set(3, 3, 3, STONE);
        SectionCache<Block, Kind> cache = new SectionCache<Block, Kind>(new Blocks(),
            new SectionCache.Limits(true, 1L << 30, 1, 600));
        SectionCache<Block, Kind>.WorldSections sections = cache.world(world, MIN_SECTION_Y, MAX_SECTION_Y);
        cache.tick(1);
        assertNotNull(sections.section(2, 0, 2));

        sections.capture(0, 0, 0);
        world.unloaded.add(columnKey(0, 0));

        assertTrue(sections.hasColumn(0, 0));
        assertSame(STONE, sections.section(0, 0, 0).data(CachedSection.index(3, 3, 3)));
    }

    @Test
    public void arrivalCaptureOfAnUnavailableColumnCachesNothing() {
        FakeWorld world = new FakeWorld();
        world.unloaded.add(columnKey(0, 0));
        SectionCache<Block, Kind> cache = new SectionCache<Block, Kind>(new Blocks(), OPEN_LIMITS);
        SectionCache<Block, Kind>.WorldSections sections = cache.world(world, MIN_SECTION_Y, MAX_SECTION_Y);
        cache.tick(1);

        sections.capture(0, 0, 0);

        assertFalse(sections.hasColumn(0, 0));
        assertEquals(0, sections.size());
    }

    @Test
    public void expiredSectionsAreCapturedAgainOnTheirNextRead() {
        FakeWorld world = new FakeWorld();
        world.set(3, 3, 3, STONE);
        SectionCache<Block, Kind> cache = new SectionCache<Block, Kind>(new Blocks(),
            new SectionCache.Limits(true, 1L << 30, 16, 20));
        SectionCache<Block, Kind>.WorldSections sections = cache.world(world, MIN_SECTION_Y, MAX_SECTION_Y);
        cache.tick(1);
        assertSame(STONE, sections.section(0, 0, 0).data(CachedSection.index(3, 3, 3)));

        world.set(3, 3, 3, GLASS);
        cache.tick(20);
        assertSame(STONE, sections.section(0, 0, 0).data(CachedSection.index(3, 3, 3)), "fresh sections are served from the cache");
        assertEquals(1, world.captures);

        cache.tick(21);
        assertSame(GLASS, sections.section(0, 0, 0).data(CachedSection.index(3, 3, 3)));
        assertEquals(2, world.captures);
    }

    @Test
    public void memoryCapEvictsTheLeastRecentlyReadSections() {
        FakeWorld world = new FakeWorld();
        for (int x = 0; x < 16 * 8; x++) {
            world.set(x, 0, 0, x % 2 == 0 ? STONE : GLASS);
        }
        SectionCache<Block, Kind> probe = new SectionCache<Block, Kind>(new Blocks(), OPEN_LIMITS);
        SectionCache<Block, Kind>.WorldSections probeSections = probe.world(world, MIN_SECTION_Y, MAX_SECTION_Y);
        probe.tick(1);
        long sectionBytes = probeSections.section(0, 0, 0).bytes();
        SectionCache<Block, Kind> cache = new SectionCache<Block, Kind>(new Blocks(),
            new SectionCache.Limits(true, sectionBytes * 4L, 16, 600));
        SectionCache<Block, Kind>.WorldSections sections = cache.world(world, MIN_SECTION_Y, MAX_SECTION_Y);

        for (int sectionX = 0; sectionX < 8; sectionX++) {
            cache.tick(10 + sectionX);
            assertNotNull(sections.section(sectionX, 0, 0));
            assertTrue(cache.bytes() <= sectionBytes * 4L);
        }
        cache.tick(100);
        int capturesBefore = world.captures;
        sections.section(7, 0, 0);
        assertEquals(capturesBefore, world.captures, "the newest section stays resident");
        sections.section(0, 0, 0);
        assertEquals(capturesBefore + 1, world.captures, "the oldest section was evicted");
    }

    @Test
    public void blockChangeEvictsTheSectionAndItsNeighboursBuriedDepth() {
        FakeWorld world = new FakeWorld();
        world.fill(-16, -16, -16, 31, 31, 31, STONE);
        SectionCache<Block, Kind> cache = new SectionCache<Block, Kind>(new Blocks(), OPEN_LIMITS);
        SectionCache<Block, Kind>.WorldSections sections = cache.world(world, MIN_SECTION_Y, MAX_SECTION_Y);
        cache.tick(1);
        assertEquals(2, sections.buriedDepth(15, 8, 8));
        CachedSection<Block, Kind> neighbour = sections.section(1, 0, 0);

        world.set(16, 8, 8, AIR);
        sections.blockChanged(16, 8, 8);

        assertEquals(0, sections.buriedDepth(15, 8, 8));
        assertEquals(1, sections.buriedDepth(14, 8, 8));
        assertFalse(neighbour == sections.section(1, 0, 0), "the changed section is captured again");
        assertSame(AIR, sections.section(1, 0, 0).data(CachedSection.index(0, 8, 8)));
    }

    @Test
    public void columnChangeEvictsEveryCachedSectionOfTheColumn() {
        FakeWorld world = new FakeWorld();
        world.fill(0, -32, 0, 15, 47, 15, GLASS);
        SectionCache<Block, Kind> cache = new SectionCache<Block, Kind>(new Blocks(), OPEN_LIMITS);
        SectionCache<Block, Kind>.WorldSections sections = cache.world(world, MIN_SECTION_Y, MAX_SECTION_Y);
        cache.tick(1);
        sections.section(0, -1, 0);
        sections.section(0, 2, 0);
        assertTrue(sections.hasColumn(0, 0));

        sections.columnChanged(0, 0);

        assertEquals(0, sections.size());
        assertFalse(sections.hasColumn(0, 0));
        assertEquals(0L, cache.bytes());
    }

    @Test
    public void disabledCacheServesNothing() {
        FakeWorld world = new FakeWorld();
        world.set(0, 0, 0, STONE);
        SectionCache<Block, Kind> cache = new SectionCache<Block, Kind>(new Blocks(), OPEN_LIMITS);
        SectionCache<Block, Kind>.WorldSections sections = cache.world(world, MIN_SECTION_Y, MAX_SECTION_Y);
        cache.tick(1);
        assertNotNull(sections.section(0, 0, 0));

        cache.configure(new SectionCache.Limits(false, 1L << 30, 16, 600));
        cache.tick(2);

        assertFalse(sections.enabled());
        assertNull(sections.section(0, 0, 0));
        assertEquals(0L, cache.bytes());
    }

    private static FakeWorld randomWorld(Random random, double density, int min, int max) {
        FakeWorld world = new FakeWorld();
        for (int x = min; x <= max; x++) {
            for (int y = min; y <= max; y++) {
                for (int z = min; z <= max; z++) {
                    double roll = random.nextDouble();
                    world.set(x, y, z, roll < density ? STONE : roll < density + ((1.0D - density) / 2.0D) ? GLASS : AIR);
                }
            }
        }
        return world;
    }

    private static int referenceDepth(FakeWorld world, int x, int y, int z) {
        if (!surrounded(world, x, y, z)) {
            return 0;
        }
        if (!surrounded(world, x + 1, y, z) || !surrounded(world, x - 1, y, z)
            || !surrounded(world, x, y + 1, z) || !surrounded(world, x, y - 1, z)
            || !surrounded(world, x, y, z + 1) || !surrounded(world, x, y, z - 1)) {
            return 1;
        }
        return 2;
    }

    private static boolean surrounded(FakeWorld world, int x, int y, int z) {
        return world.occluding(x, y, z) && world.occluding(x + 1, y, z) && world.occluding(x - 1, y, z)
            && world.occluding(x, y + 1, z) && world.occluding(x, y - 1, z)
            && world.occluding(x, y, z + 1) && world.occluding(x, y, z - 1);
    }

    private static long columnKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private enum Kind {
        AIR, CAVE_AIR, STONE, GLASS
    }

    private record Block(Kind kind, int state) {
    }

    private static final class FakeWorld implements SectionSource<Block, Kind>, ContentView<Block, Kind> {
        private static final int ORIGIN = 48;
        private static final int SPAN = 192;

        private final Block[] blocks = new Block[SPAN * SPAN * SPAN];
        private final Set<Long> unloaded = new HashSet<Long>();
        private int captures;

        private void set(int x, int y, int z, Block block) {
            blocks[slot(x, y, z)] = block;
        }

        private void fill(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Block block) {
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        set(x, y, z, block);
                    }
                }
            }
        }

        @Override
        public boolean columnAvailable(int chunkX, int chunkZ) {
            return !unloaded.contains(columnKey(chunkX, chunkZ));
        }

        @Override
        public boolean capture(int sectionX, int sectionY, int sectionZ, CachedSection.Builder<Block, Kind> builder) {
            if (!columnAvailable(sectionX, sectionZ)) {
                return false;
            }
            captures++;
            for (int index = 0; index < CachedSection.CELLS; index++) {
                int x = (sectionX << 4) + (index & 15);
                int y = (sectionY << 4) + (index >> 8);
                int z = (sectionZ << 4) + ((index >> 4) & 15);
                Block block = get(x, y, z);
                builder.set(index, block.kind == Kind.CAVE_AIR ? AIR : block, block.kind);
            }
            return true;
        }

        @Override
        public void discardColumn(int chunkX, int chunkZ) {
        }

        @Override
        public void endTick() {
        }

        @Override
        public Kind material(int x, int y, int z) {
            if (y < (MIN_SECTION_Y << 4) || y > (MAX_SECTION_Y << 4) + 15) {
                return null;
            }
            return get(x, y, z).kind;
        }

        @Override
        public int getMinHeight() {
            return MIN_SECTION_Y << 4;
        }

        @Override
        public int getMaxHeight() {
            return (MAX_SECTION_Y + 1) << 4;
        }

        @Override
        public Block sampleBlockData(int x, int y, int z) {
            return get(x, y, z);
        }

        @Override
        public boolean isChunkReady(int x, int z) {
            return true;
        }

        @Override
        public void requestChunk(int x, int z) {
        }

        @Override
        public long getRevision() {
            return 0L;
        }

        @Override
        public UUID worldId() {
            return null;
        }

        @Override
        public String sampleBiome(int x, int y, int z) {
            return null;
        }

        @Override
        public int biomeId(int x, int y, int z) {
            return -1;
        }

        @Override
        public BlockEntitySample sampleBlockEntity(int x, int y, int z) {
            return null;
        }

        @Override
        public int getLight(int x, int y, int z) {
            return LIGHT_UNAVAILABLE;
        }

        @Override
        public int getSkyDarken() {
            return 0;
        }

        private static int slot(int x, int y, int z) {
            return (((x + ORIGIN) * SPAN) + (y + ORIGIN)) * SPAN + (z + ORIGIN);
        }

        private Block get(int x, int y, int z) {
            int ox = x + ORIGIN;
            int oy = y + ORIGIN;
            int oz = z + ORIGIN;
            if (ox < 0 || oy < 0 || oz < 0 || ox >= SPAN || oy >= SPAN || oz >= SPAN) {
                return AIR;
            }
            Block block = blocks[slot(x, y, z)];
            return block == null ? AIR : block;
        }

        private boolean occluding(int x, int y, int z) {
            if (y < (MIN_SECTION_Y << 4) || y > (MAX_SECTION_Y << 4) + 15) {
                return false;
            }
            return get(x, y, z).kind == Kind.STONE;
        }
    }

    private static final class Blocks implements BlockStates<Block, Kind> {
        @Override
        public Block air() {
            return AIR;
        }

        @Override
        public Block occluded() {
            return null;
        }

        @Override
        public boolean isOccluded(Block block) {
            return false;
        }

        @Override
        public Kind material(Block block) {
            return block.kind;
        }

        @Override
        public String materialName(Kind material) {
            return material.name();
        }

        @Override
        public boolean blockEntityCandidate(Kind material) {
            return false;
        }

        @Override
        public boolean isAir(Kind material) {
            return material == Kind.AIR || material == Kind.CAVE_AIR;
        }

        @Override
        public boolean isOccluding(Kind material) {
            return material == Kind.STONE;
        }

        @Override
        public boolean occludes(Block block) {
            return block != null && isOccluding(material(block));
        }

        @Override
        public boolean requiresTransform(Block block) {
            return false;
        }

        @Override
        public Block transform(Block block, AxisPermutation permutation) {
            return block;
        }

        @Override
        public StateProperties properties(Block block) {
            return StateProperties.EMPTY;
        }

        @Override
        public Block withProperties(Block block, StateProperties properties) {
            return block;
        }
    }
}
