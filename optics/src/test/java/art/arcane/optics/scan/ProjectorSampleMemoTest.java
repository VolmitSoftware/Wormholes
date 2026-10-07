package art.arcane.optics.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.function.Predicate;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import art.arcane.optics.math.Box;

import org.junit.jupiter.api.Test;
import art.arcane.optics.claim.ProjectedBlockClaim;
import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.internal.occlusion.ProjectorHoldProof;
import art.arcane.optics.state.StateProperties;
import art.arcane.optics.view.BlockStates;
import art.arcane.optics.view.ContentView;
import art.arcane.optics.view.WorldChangeTracker;

public final class ProjectorSampleMemoTest {
    private static final UUID LOCAL_WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final int[][] FIRST_OCCLUSION_SHELL = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };
    private static final int[][] SECOND_OCCLUSION_SHELL = {
        {2, 0, 0}, {1, 1, 0}, {1, -1, 0}, {1, 0, 1}, {1, 0, -1},
        {-2, 0, 0}, {-1, 1, 0}, {-1, -1, 0}, {-1, 0, 1}, {-1, 0, -1},
        {0, 2, 0}, {0, 1, 1}, {0, 1, -1}, {0, -2, 0}, {0, -1, 1}, {0, -1, -1},
        {0, 0, 2}, {0, 0, -2}
    };

    @Test
    public void localStaleAnswersWhatRefreshLocalWouldDoWithoutClearing() {
        ProjectorSampleMemo<TestBlock, TestMaterial, TestView> memo = memo();
        assertTrue(memo.localStale(false, 4L, 4096));
        assertTrue(memo.refreshLocal(false, false, 4L, 4096));
        assertFalse(memo.localStale(false, 4L, 4096));
        assertTrue(memo.localStale(true, 4L, 4096));
        assertTrue(memo.localStale(false, 5L, 4096));
        assertFalse(memo.localStale(false, 4L, 4096), "asking must not refresh the memo");
        assertFalse(memo.refreshLocal(false, false, 4L, 4096));
        assertTrue(memo.refreshLocal(true, false, 4L, 4096));
    }

    @Test
    public void sampleMemoKeepsOneEntryPerViewAndCell() {
        ProjectorSampleMemo<TestBlock, TestMaterial, TestView> memo = memo();
        FakeWorldView first = new FakeWorldView();
        FakeWorldView second = new FakeWorldView();
        ProjectorSample<TestBlock, TestView> firstSample = ProjectorSample.noSample();
        ProjectorSample<TestBlock, TestView> secondSample = ProjectorSample.maskAir(blockData(TestMaterial.AIR));

        assertNull(memo.cachedSample(first, 3, 70, -4));
        memo.cacheSample(first, 3, 70, -4, firstSample);
        assertSame(firstSample, memo.cachedSample(first, 3, 70, -4));
        assertNull(memo.cachedSample(first, 4, 70, -4));
        assertNull(memo.cachedSample(second, 3, 70, -4));

        memo.cacheSample(second, 3, 70, -4, secondSample);
        assertSame(secondSample, memo.cachedSample(second, 3, 70, -4));
        assertSame(firstSample, memo.cachedSample(first, 3, 70, -4));

        memo.clearDestinationSamples();
        assertNull(memo.cachedSample(first, 3, 70, -4));
        assertNull(memo.cachedSample(second, 3, 70, -4));
    }

    @Test
    public void occlusionDepthKeepsOneMaterialBackingLayerAndDropsOnlyDeepInterior() {
        TestBlock stone = blockData(TestMaterial.STONE);
        FakeWorldView exposed = new FakeWorldView(stone);
        exposed.put(1, 0, 0, blockData(TestMaterial.AIR));
        FakeWorldView backing = new FakeWorldView(stone);
        backing.put(2, 0, 0, blockData(TestMaterial.AIR));
        FakeWorldView deep = new FakeWorldView(stone);
        ProjectorSampleMemo<TestBlock, TestMaterial, TestView> memo = memo(
            material -> material == TestMaterial.STONE);

        assertEquals(0, memo.occlusionDepthInView(exposed, 0, 0, 0, stone));
        assertEquals(1, memo.occlusionDepthInView(backing, 0, 0, 0, stone));
        assertEquals(2, memo.occlusionDepthInView(deep, 0, 0, 0, stone));
    }

    @Test
    public void everyFirstShellOccupancyCombinationMatchesTheReferenceDepth() {
        TestBlock stone = blockData(TestMaterial.STONE);
        TestBlock air = blockData(TestMaterial.AIR);
        ShellWorldView view = new ShellWorldView(stone, air);
        ProjectorSampleMemo<TestBlock, TestMaterial, TestView> memo = memo(material -> material == TestMaterial.STONE);
        setShell(view, SECOND_OCCLUSION_SHELL, -1);

        int fullShellMask = (1 << FIRST_OCCLUSION_SHELL.length) - 1;
        for (int mask = 0; mask <= fullShellMask; mask++) {
            setShell(view, FIRST_OCCLUSION_SHELL, mask);
            memo.clearDestinationSamples();
            assertEquals(referenceOcclusionDepth(view), memo.occlusionDepthInView(view, 0, 0, 0, stone),
                "first-shell mask " + mask);
        }
    }

    @Test
    public void everySecondShellOccupancyCombinationMatchesTheReferenceDepth() {
        TestBlock stone = blockData(TestMaterial.STONE);
        TestBlock air = blockData(TestMaterial.AIR);
        ShellWorldView view = new ShellWorldView(stone, air);
        ProjectorSampleMemo<TestBlock, TestMaterial, TestView> memo = memo(material -> material == TestMaterial.STONE);
        setShell(view, FIRST_OCCLUSION_SHELL, -1);

        int fullShellMask = (1 << SECOND_OCCLUSION_SHELL.length) - 1;
        for (int mask = 0; mask <= fullShellMask; mask++) {
            setShell(view, SECOND_OCCLUSION_SHELL, mask);
            memo.clearDestinationSamples();
            assertEquals(referenceOcclusionDepth(view), memo.occlusionDepthInView(view, 0, 0, 0, stone),
                "second-shell mask " + mask);
        }
    }

    @Test
    public void randomizedShellsMatchTheReferenceDepth() {
        TestBlock stone = blockData(TestMaterial.STONE);
        TestBlock air = blockData(TestMaterial.AIR);
        ShellWorldView view = new ShellWorldView(stone, air);
        ProjectorSampleMemo<TestBlock, TestMaterial, TestView> memo = memo(material -> material == TestMaterial.STONE);
        Random random = new Random(0x5EED5EEDL);

        for (int iteration = 0; iteration < 10_000; iteration++) {
            setRandomShell(view, FIRST_OCCLUSION_SHELL, random);
            setRandomShell(view, SECOND_OCCLUSION_SHELL, random);
            memo.clearDestinationSamples();
            assertEquals(referenceOcclusionDepth(view), memo.occlusionDepthInView(view, 0, 0, 0, stone),
                "random iteration " + iteration);
        }
    }

    @Test
    public void buriedOcclusionReadsEachUniqueShellCellOnlyOnce() {
        TestBlock stone = blockData(TestMaterial.STONE);
        TestBlock air = blockData(TestMaterial.AIR);
        ShellWorldView view = new ShellWorldView(stone, air);
        ProjectorSampleMemo<TestBlock, TestMaterial, TestView> memo = memo(material -> material == TestMaterial.STONE);
        setShell(view, FIRST_OCCLUSION_SHELL, -1);
        setShell(view, SECOND_OCCLUSION_SHELL, -1);

        assertEquals(2, memo.occlusionDepthInView(view, 0, 0, 0, stone));
        assertEquals(24, view.reads);
        assertEquals(2, memo.occlusionDepthInView(view, 0, 0, 0, stone));
        assertEquals(24, view.reads);
    }

    @Test
    public void occlusionDepthPrefersTheViewAnswerWithoutProbing() {
        TestBlock stone = blockData(TestMaterial.STONE);
        ShellWorldView view = new ShellWorldView(stone, blockData(TestMaterial.AIR));
        view.knownDepth = 1;
        ProjectorSampleMemo<TestBlock, TestMaterial, TestView> memo = memo(material -> material == TestMaterial.STONE);

        assertEquals(1, memo.occlusionDepthInView(view, 0, 0, 0, stone));
        assertEquals(0, view.reads, "a view that knows the buried depth must not be probed");

        view.knownDepth = -1;
        assertEquals(0, memo.occlusionDepthInView(view, 0, 0, 0, stone));
        assertEquals(1, view.reads, "an unknown view depth falls back to neighbour probing");
    }

    @Test
    public void nonOccludingSelfNeverAsksTheView() {
        TestBlock air = blockData(TestMaterial.AIR);
        ShellWorldView view = new ShellWorldView(blockData(TestMaterial.STONE), air);
        view.knownDepth = 2;
        ProjectorSampleMemo<TestBlock, TestMaterial, TestView> memo = memo(material -> material == TestMaterial.STONE);

        assertEquals(0, memo.occlusionDepthInView(view, 0, 0, 0, air));
        assertEquals(0, view.depthQueries);
    }

    @Test
    public void fullBrightClaimMatchingStillRequiresCurrentRemoteCorrespondence() {
        TestBlock stone = blockData(TestMaterial.STONE);
        FakeWorldView first = new FakeWorldView(stone);
        FakeWorldView second = new FakeWorldView(stone);
        ProjectorSample<TestBlock, TestView> sample = new ProjectorSample<TestBlock, TestView>(ProjectorSample.Kind.BLOCK, stone, first, 41L);
        ProjectedBlockClaim<TestBlock, TestView> matching = new ProjectedBlockClaim<TestBlock, TestView>(
            stone, first, 41L, false, ProjectedBlockClaim.LightingPolicy.FULL_BRIGHT);
        ProjectedBlockClaim<TestBlock, TestView> moved = new ProjectedBlockClaim<TestBlock, TestView>(
            stone, first, 42L, false, ProjectedBlockClaim.LightingPolicy.FULL_BRIGHT);
        ProjectedBlockClaim<TestBlock, TestView> differentView = new ProjectedBlockClaim<TestBlock, TestView>(
            stone, second, 41L, false, ProjectedBlockClaim.LightingPolicy.FULL_BRIGHT);

        assertTrue(sample.matchesClaim(
            matching, stone, false, ProjectedBlockClaim.LightingPolicy.FULL_BRIGHT));
        assertFalse(sample.matchesClaim(
            moved, stone, false, ProjectedBlockClaim.LightingPolicy.FULL_BRIGHT));
        assertFalse(sample.matchesClaim(
            differentView, stone, false, ProjectedBlockClaim.LightingPolicy.FULL_BRIGHT));
    }

    @Test
    public void remoteClaimMismatchSkipsBlockDataEquality() {
        AtomicInteger equalityCalls = new AtomicInteger();
        TestBlock claimData = blockData(TestMaterial.STONE, equalityCalls);
        TestBlock projectedData = blockData(TestMaterial.STONE);
        FakeWorldView view = new FakeWorldView(projectedData);
        ProjectorSample<TestBlock, TestView> sample = new ProjectorSample<TestBlock, TestView>(ProjectorSample.Kind.BLOCK, projectedData, view, 41L);
        ProjectedBlockClaim<TestBlock, TestView> moved = new ProjectedBlockClaim<TestBlock, TestView>(claimData, view, 42L, false);

        assertFalse(sample.matchesClaim(moved, projectedData, false));
        assertEquals(0, equalityCalls.get());
    }

    @Test
    public void identicalLightViewSkipsViewEquality() {
        TestBlock stone = blockData(TestMaterial.STONE);
        TestView view = identityOnlyView();
        ProjectorSample<TestBlock, TestView> sample = new ProjectorSample<TestBlock, TestView>(ProjectorSample.Kind.BLOCK, stone, view, 41L);
        ProjectedBlockClaim<TestBlock, TestView> claim = new ProjectedBlockClaim<TestBlock, TestView>(stone, view, 41L, false);

        assertTrue(sample.matchesClaim(claim, stone, false));
    }

    @Test
    public void localAirMemoSamplesEachCellOnlyOnce() {
        ProjectorSampleMemo<TestBlock, TestMaterial, TestView> memo = memo();
        FakeWorldView view = new FakeWorldView();
        view.put(1, 65, 2, blockData(TestMaterial.AIR));
        view.put(1, 66, 2, blockData(TestMaterial.STONE));

        assertTrue(memo.isLocalAir(view, 1, 65, 2));
        assertTrue(memo.isLocalAir(view, 1, 65, 2));
        assertFalse(memo.isLocalAir(view, 1, 66, 2));
        assertFalse(memo.isLocalAir(view, 1, 66, 2));
        assertEquals(2, view.reads, "memoized cells must not be re-sampled");

        assertFalse(memo.isLocalAir(view, 9, 9, 9));
        assertEquals(3, view.reads);
    }

    @Test
    public void discardDropsEveryMemoizedView() {
        ProjectorSampleMemo<TestBlock, TestMaterial, TestView> memo = memo();
        FakeWorldView view = new FakeWorldView();
        view.put(0, 0, 0, blockData(TestMaterial.AIR));
        memo.cacheSample(view, 0, 0, 0, ProjectorSample.noSample());
        assertTrue(memo.isLocalAir(view, 0, 0, 0));

        memo.discard();

        assertNull(memo.cachedSample(view, 0, 0, 0));
        assertTrue(memo.isLocalAir(view, 0, 0, 0));
        assertEquals(2, view.reads, "a discarded local air memo must re-read the view");
    }

    private static TestBlock blockData(TestMaterial material) {
        return blockData(material, null);
    }

    private static TestBlock blockData(TestMaterial material, AtomicInteger equalityCalls) {
        return (TestBlock) Proxy.newProxyInstance(TestBlock.class.getClassLoader(), new Class<?>[] { TestBlock.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "getMaterial" -> material;
                case "toString", "getAsString" -> String.valueOf(material);
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> {
                    if (equalityCalls != null) {
                        equalityCalls.incrementAndGet();
                    }
                    yield Boolean.valueOf(proxy == args[0]);
                }
                case "clone" -> proxy;
                default -> null;
            });
    }

    private static TestView identityOnlyView() {
        return (TestView) Proxy.newProxyInstance(
            TestView.class.getClassLoader(), new Class<?>[] { TestView.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "equals" -> throw new AssertionError("identity match must not call equals");
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                default -> null;
            });
    }

    private static void setShell(ShellWorldView view, int[][] offsets, int mask) {
        for (int index = 0; index < offsets.length; index++) {
            int[] offset = offsets[index];
            view.setOccluding(offset[0], offset[1], offset[2], mask < 0 || (mask & (1 << index)) != 0);
        }
    }

    private static void setRandomShell(ShellWorldView view, int[][] offsets, Random random) {
        for (int[] offset : offsets) {
            view.setOccluding(offset[0], offset[1], offset[2], random.nextBoolean());
        }
    }

    private static int referenceOcclusionDepth(ShellWorldView view) {
        if (!referenceSurrounded(view, 0, 0, 0)) {
            return 0;
        }
        if (!referenceSurrounded(view, 1, 0, 0)
            || !referenceSurrounded(view, -1, 0, 0)
            || !referenceSurrounded(view, 0, 1, 0)
            || !referenceSurrounded(view, 0, -1, 0)
            || !referenceSurrounded(view, 0, 0, 1)
            || !referenceSurrounded(view, 0, 0, -1)) {
            return 1;
        }
        return 2;
    }

    private static boolean referenceSurrounded(ShellWorldView view, int x, int y, int z) {
        return view.isOccluding(x, y, z)
            && view.isOccluding(x + 1, y, z)
            && view.isOccluding(x - 1, y, z)
            && view.isOccluding(x, y + 1, z)
            && view.isOccluding(x, y - 1, z)
            && view.isOccluding(x, y, z + 1)
            && view.isOccluding(x, y, z - 1);
    }

    private static final class ShellWorldView implements TestView {
        private final TestBlock occludingData;
        private final TestBlock openData;
        private final boolean[][][] occluding;
        private int reads;
        private int knownDepth;
        private int depthQueries;

        private ShellWorldView(TestBlock occludingData, TestBlock openData) {
            this.occludingData = occludingData;
            this.openData = openData;
            this.occluding = new boolean[5][5][5];
            this.occluding[2][2][2] = true;
            this.knownDepth = -1;
            this.depthQueries = 0;
        }

        @Override
        public int buriedDepth(int x, int y, int z) {
            depthQueries++;
            return knownDepth;
        }

        private void setOccluding(int x, int y, int z, boolean value) {
            occluding[x + 2][y + 2][z + 2] = value;
        }

        private boolean isOccluding(int x, int y, int z) {
            if (x < -2 || x > 2 || y < -2 || y > 2 || z < -2 || z > 2) {
                return false;
            }
            return occluding[x + 2][y + 2][z + 2];
        }


        @Override
        public int getMinHeight() {
            return -64;
        }

        @Override
        public int getMaxHeight() {
            return 320;
        }

        @Override
        public TestBlock sampleBlockData(int x, int y, int z) {
            reads++;
            return isOccluding(x, y, z) ? occludingData : openData;
        }



    }

    private static final class FakeWorldView implements TestView {
        private final Map<String, TestBlock> blocks = new HashMap<String, TestBlock>();
        private final TestBlock defaultData;
        private int reads;
        private boolean ready = true;

        private FakeWorldView() {
            this(null);
        }

        private FakeWorldView(TestBlock defaultData) {
            this.defaultData = defaultData;
        }

        private void put(int x, int y, int z, TestBlock data) {
            blocks.put(key(x, y, z), data);
        }


        @Override
        public int getMinHeight() {
            return -64;
        }

        @Override
        public int getMaxHeight() {
            return 320;
        }

        @Override
        public TestBlock sampleBlockData(int x, int y, int z) {
            reads++;
            return blocks.getOrDefault(key(x, y, z), defaultData);
        }

        @Override
        public boolean isChunkReady(int x, int z) {
            return ready;
        }




        private static String key(int x, int y, int z) {
            return x + ":" + y + ":" + z;
        }
    }

    @Test
    public void localChangeKeepingAirAndOcclusionLeavesTheRegionClean() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        ProjectorSampleMemo<TestBlock, TestMaterial, TestView> memo = trackedMemo(tracker);
        FakeWorldView view = new FakeWorldView(blockData(TestMaterial.AIR));
        view.put(5, 64, 5, blockData(TestMaterial.STONE));
        view.put(6, 64, 5, blockData(TestMaterial.STONE));
        scanLocal(memo);
        assertFalse(memo.isLocalAir(view, 5, 64, 5));
        assertEquals(ProjectorHoldProof.Occupancy.OCCLUDING, memo.localOccupancy(view, 6, 64, 5));

        view.put(5, 64, 5, blockData(TestMaterial.DIRT));
        view.put(6, 64, 5, blockData(TestMaterial.DIRT));
        tracker.markChanged(LOCAL_WORLD, 5, 64, 5);
        tracker.markChanged(LOCAL_WORLD, 6, 64, 5);
        tracker.markChanged(LOCAL_WORLD, 7, 64, 5);

        assertFalse(memo.localRegionDirty(view, LOCAL_WORLD));
        int reads = view.reads;
        assertFalse(memo.localRegionDirty(view, LOCAL_WORLD));
        assertEquals(reads, view.reads);
    }

    @Test
    public void localChangeFlippingAMemoizedAirCellDirtiesTheRegion() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        ProjectorSampleMemo<TestBlock, TestMaterial, TestView> memo = trackedMemo(tracker);
        FakeWorldView view = new FakeWorldView(blockData(TestMaterial.AIR));
        scanLocal(memo);
        assertTrue(memo.isLocalAir(view, 5, 64, 5));

        view.put(5, 64, 5, blockData(TestMaterial.GLASS));
        tracker.markChanged(LOCAL_WORLD, 5, 64, 5);

        assertTrue(memo.localRegionDirty(view, LOCAL_WORLD));
    }

    @Test
    public void localChangeFlippingMemoizedOcclusionDirtiesTheRegion() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        ProjectorSampleMemo<TestBlock, TestMaterial, TestView> memo = trackedMemo(tracker);
        FakeWorldView view = new FakeWorldView(blockData(TestMaterial.STONE));
        scanLocal(memo);
        assertEquals(ProjectorHoldProof.Occupancy.OCCLUDING, memo.localOccupancy(view, 5, 64, 5));

        view.put(5, 64, 5, blockData(TestMaterial.GLASS));
        tracker.markChanged(LOCAL_WORLD, 5, 64, 5);

        assertTrue(memo.localRegionDirty(view, LOCAL_WORLD));
    }

    @Test
    public void localChunkLoadOrUnreadyMemoizedCellDirtiesTheRegion() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        ProjectorSampleMemo<TestBlock, TestMaterial, TestView> memo = trackedMemo(tracker);
        FakeWorldView view = new FakeWorldView(blockData(TestMaterial.AIR));
        scanLocal(memo);
        assertTrue(memo.isLocalAir(view, 5, 64, 5));

        tracker.markChanged(LOCAL_WORLD, 5, 5);
        assertTrue(memo.localRegionDirty(view, LOCAL_WORLD));

        scanLocal(memo);
        tracker.markChanged(LOCAL_WORLD, 5, 64, 5);
        view.ready = false;
        assertTrue(memo.localRegionDirty(view, LOCAL_WORLD));
    }

    @Test
    public void localRegionWithoutARectIsDirty() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        ProjectorSampleMemo<TestBlock, TestMaterial, TestView> memo = trackedMemo(tracker);

        assertTrue(memo.localRegionDirty(new FakeWorldView(), LOCAL_WORLD));
    }

    private static void scanLocal(ProjectorSampleMemo<TestBlock, TestMaterial, TestView> memo) {
        memo.expandLocalRegionRect(new Box(0.0D, 16.0D, 60.0D, 70.0D, 0.0D, 16.0D));
        memo.markLocalScanned();
    }

    private static ProjectorSampleMemo<TestBlock, TestMaterial, TestView> trackedMemo(WorldChangeTracker tracker) {
        return new ProjectorSampleMemo<>(new TestBlocks(material -> material == TestMaterial.STONE || material == TestMaterial.DIRT),
            () -> tracker);
    }

    private static ProjectorSampleMemo<TestBlock, TestMaterial, TestView> memo() {
        return memo(material -> material == TestMaterial.STONE);
    }

    private static ProjectorSampleMemo<TestBlock, TestMaterial, TestView> memo(Predicate<TestMaterial> occlusion) {
        return new ProjectorSampleMemo<>(new TestBlocks(occlusion), () -> null);
    }

    private enum TestMaterial {
        AIR, STONE, DIRT, GLASS
    }

    private interface TestBlock {
        TestMaterial getMaterial();
    }

    private interface TestView extends ContentView<TestBlock, TestMaterial> {
        default TestMaterial material(int x, int y, int z) {
            TestBlock block = sampleBlockData(x, y, z);
            return block == null ? null : block.getMaterial();
        }

        default boolean isChunkReady(int x, int z) {
            return true;
        }

        default void requestChunk(int x, int z) {
        }

        default long getRevision() {
            return 0L;
        }

        default UUID worldId() {
            return null;
        }

        default String sampleBiome(int x, int y, int z) {
            return null;
        }

        default int biomeId(int x, int y, int z) {
            return -1;
        }

        default BlockEntitySample sampleBlockEntity(int x, int y, int z) {
            return null;
        }

        default int getLight(int x, int y, int z) {
            return LIGHT_UNAVAILABLE;
        }

        default int getSkyDarken() {
            return 0;
        }
    }

    private record TestBlocks(Predicate<TestMaterial> occlusion) implements BlockStates<TestBlock, TestMaterial> {
        public TestBlock air() {
            return blockData(TestMaterial.AIR);
        }

        public TestBlock occluded() {
            return null;
        }

        public boolean isOccluded(TestBlock block) {
            return false;
        }

        public TestMaterial material(TestBlock block) {
            return block.getMaterial();
        }

        public String materialName(TestMaterial material) {
            return material.name();
        }

        public boolean blockEntityCandidate(TestMaterial material) {
            return false;
        }

        public boolean isAir(TestMaterial material) {
            return material == TestMaterial.AIR;
        }

        public boolean isOccluding(TestMaterial material) {
            return occlusion.test(material);
        }

        public boolean occludes(TestBlock block) {
            return block != null && isOccluding(material(block));
        }

        public boolean requiresTransform(TestBlock block) {
            return false;
        }

        public TestBlock transform(TestBlock block, AxisPermutation permutation) {
            return block;
        }

        public StateProperties properties(TestBlock block) {
            return StateProperties.EMPTY;
        }

        public TestBlock withProperties(TestBlock block, StateProperties properties) {
            return block;
        }
    }
}
