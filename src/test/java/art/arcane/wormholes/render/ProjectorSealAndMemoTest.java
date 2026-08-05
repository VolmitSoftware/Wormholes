package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.render.view.ProjectionWorldView;

public final class ProjectorSealAndMemoTest {
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
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[] { BlockData.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "getMaterial" -> material;
                case "toString", "getAsString" -> String.valueOf(material);
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == args[0]);
                case "clone" -> proxy;
                default -> null;
            });
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
