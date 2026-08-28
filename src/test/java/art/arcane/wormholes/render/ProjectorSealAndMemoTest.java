package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.render.view.ProjectionWorldView;

public final class ProjectorSealAndMemoTest {
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
    public void aSealIsInertUntilAPassEnablesIt() {
        ProjectorBlackoutSeal seal = new ProjectorBlackoutSeal();
        assertFalse(seal.isEnabled());
        seal.disable();
        assertFalse(seal.isEnabled());
    }

    @Test
    public void sampleMemoKeepsOneEntryPerViewAndCell() {
        ProjectorSampleMemo memo = new ProjectorSampleMemo();
        FakeWorldView first = new FakeWorldView();
        FakeWorldView second = new FakeWorldView();
        ProjectorSample firstSample = ProjectorSample.noSample();
        ProjectorSample secondSample = ProjectorSample.maskAir(blockData(Material.AIR));

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
        BlockData stone = blockData(Material.STONE);
        FakeWorldView exposed = new FakeWorldView(stone);
        exposed.put(1, 0, 0, blockData(Material.AIR));
        FakeWorldView backing = new FakeWorldView(stone);
        backing.put(2, 0, 0, blockData(Material.AIR));
        FakeWorldView deep = new FakeWorldView(stone);
        ProjectorSampleMemo memo = new ProjectorSampleMemo(
            material -> material == Material.STONE);

        assertEquals(0, memo.occlusionDepthInView(exposed, 0, 0, 0, stone));
        assertEquals(1, memo.occlusionDepthInView(backing, 0, 0, 0, stone));
        assertEquals(2, memo.occlusionDepthInView(deep, 0, 0, 0, stone));
    }

    @Test
    public void everyFirstShellOccupancyCombinationMatchesTheReferenceDepth() {
        BlockData stone = blockData(Material.STONE);
        BlockData air = blockData(Material.AIR);
        ShellWorldView view = new ShellWorldView(stone, air);
        ProjectorSampleMemo memo = new ProjectorSampleMemo(material -> material == Material.STONE);
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
        BlockData stone = blockData(Material.STONE);
        BlockData air = blockData(Material.AIR);
        ShellWorldView view = new ShellWorldView(stone, air);
        ProjectorSampleMemo memo = new ProjectorSampleMemo(material -> material == Material.STONE);
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
        BlockData stone = blockData(Material.STONE);
        BlockData air = blockData(Material.AIR);
        ShellWorldView view = new ShellWorldView(stone, air);
        ProjectorSampleMemo memo = new ProjectorSampleMemo(material -> material == Material.STONE);
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
        BlockData stone = blockData(Material.STONE);
        BlockData air = blockData(Material.AIR);
        ShellWorldView view = new ShellWorldView(stone, air);
        ProjectorSampleMemo memo = new ProjectorSampleMemo(material -> material == Material.STONE);
        setShell(view, FIRST_OCCLUSION_SHELL, -1);
        setShell(view, SECOND_OCCLUSION_SHELL, -1);

        assertEquals(2, memo.occlusionDepthInView(view, 0, 0, 0, stone));
        assertEquals(24, view.reads);
        assertEquals(2, memo.occlusionDepthInView(view, 0, 0, 0, stone));
        assertEquals(24, view.reads);
    }

    @Test
    public void fullBrightClaimMatchingStillRequiresCurrentRemoteCorrespondence() {
        BlockData stone = blockData(Material.STONE);
        FakeWorldView first = new FakeWorldView(stone);
        FakeWorldView second = new FakeWorldView(stone);
        ProjectorSample sample = new ProjectorSample(ProjectorSample.Kind.BLOCK, stone, first, 41L);
        ProjectedBlockClaim matching = new ProjectedBlockClaim(
            stone, first, 41L, false, ProjectedBlockClaim.LightingPolicy.FULL_BRIGHT);
        ProjectedBlockClaim moved = new ProjectedBlockClaim(
            stone, first, 42L, false, ProjectedBlockClaim.LightingPolicy.FULL_BRIGHT);
        ProjectedBlockClaim differentView = new ProjectedBlockClaim(
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
        BlockData claimData = blockData(Material.STONE, equalityCalls);
        BlockData projectedData = blockData(Material.STONE);
        FakeWorldView view = new FakeWorldView(projectedData);
        ProjectorSample sample = new ProjectorSample(ProjectorSample.Kind.BLOCK, projectedData, view, 41L);
        ProjectedBlockClaim moved = new ProjectedBlockClaim(claimData, view, 42L, false);

        assertFalse(sample.matchesClaim(moved, projectedData, false));
        assertEquals(0, equalityCalls.get());
    }

    @Test
    public void identicalLightViewSkipsViewEquality() {
        BlockData stone = blockData(Material.STONE);
        ProjectionWorldView view = identityOnlyView();
        ProjectorSample sample = new ProjectorSample(ProjectorSample.Kind.BLOCK, stone, view, 41L);
        ProjectedBlockClaim claim = new ProjectedBlockClaim(stone, view, 41L, false);

        assertTrue(sample.matchesClaim(claim, stone, false));
    }

    @Test
    public void localAirMemoSamplesEachCellOnlyOnce() {
        ProjectorSampleMemo memo = new ProjectorSampleMemo();
        FakeWorldView view = new FakeWorldView();
        view.put(1, 65, 2, blockData(Material.AIR));
        view.put(1, 66, 2, blockData(Material.STONE));

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
        ProjectorSampleMemo memo = new ProjectorSampleMemo();
        FakeWorldView view = new FakeWorldView();
        view.put(0, 0, 0, blockData(Material.AIR));
        memo.cacheSample(view, 0, 0, 0, ProjectorSample.noSample());
        assertTrue(memo.isLocalAir(view, 0, 0, 0));

        memo.discard();

        assertNull(memo.cachedSample(view, 0, 0, 0));
        assertTrue(memo.isLocalAir(view, 0, 0, 0));
        assertEquals(2, view.reads, "a discarded local air memo must re-read the view");
    }

    private static BlockData blockData(Material material) {
        return blockData(material, null);
    }

    private static BlockData blockData(Material material, AtomicInteger equalityCalls) {
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[] { BlockData.class },
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

    private static ProjectionWorldView identityOnlyView() {
        return (ProjectionWorldView) Proxy.newProxyInstance(
            ProjectionWorldView.class.getClassLoader(), new Class<?>[] { ProjectionWorldView.class },
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

    private static final class ShellWorldView implements ProjectionWorldView {
        private final BlockData occludingData;
        private final BlockData openData;
        private final boolean[][][] occluding;
        private int reads;

        private ShellWorldView(BlockData occludingData, BlockData openData) {
            this.occludingData = occludingData;
            this.openData = openData;
            this.occluding = new boolean[5][5][5];
            this.occluding[2][2][2] = true;
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
        public World getWorld() {
            return null;
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
        public BlockData sampleBlockData(int x, int y, int z) {
            reads++;
            return isOccluding(x, y, z) ? occludingData : openData;
        }

        @Override
        public String sampleBiome(int x, int y, int z) {
            return null;
        }

        @Override
        public int getLight(int x, int y, int z) {
            return ProjectionWorldView.LIGHT_UNAVAILABLE;
        }

        @Override
        public int getSkyDarken() {
            return 0;
        }
    }

    private static final class FakeWorldView implements ProjectionWorldView {
        private final Map<String, BlockData> blocks = new HashMap<String, BlockData>();
        private final BlockData defaultData;
        private int reads;

        private FakeWorldView() {
            this(null);
        }

        private FakeWorldView(BlockData defaultData) {
            this.defaultData = defaultData;
        }

        private void put(int x, int y, int z, BlockData data) {
            blocks.put(key(x, y, z), data);
        }

        @Override
        public World getWorld() {
            return null;
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
        public BlockData sampleBlockData(int x, int y, int z) {
            reads++;
            return blocks.getOrDefault(key(x, y, z), defaultData);
        }

        @Override
        public String sampleBiome(int x, int y, int z) {
            return null;
        }

        @Override
        public int getLight(int x, int y, int z) {
            return ProjectionWorldView.LIGHT_UNAVAILABLE;
        }

        @Override
        public int getSkyDarken() {
            return 0;
        }

        private static String key(int x, int y, int z) {
            return x + ":" + y + ":" + z;
        }
    }
}
