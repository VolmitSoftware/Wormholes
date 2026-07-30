package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.util.Direction;

public final class ProjectorSealAndMemoTest {
    @Test
    public void rectangularProjectionUsesAnExactFiveSidedShell() {
        LongOpenHashSet geometry = rectangularGeometry(0, 4, 0, 6, 0, 6);
        Direction normal = Direction.E;
        Direction right = Direction.S;
        Direction up = Direction.U;

        assertTrue(seals(geometry, 0, 3, 3, normal, right, up, 1));
        assertFalse(seals(geometry, 1, 3, 3, normal, right, up, 1));
        assertFalse(seals(geometry, 4, 3, 3, normal, right, up, 1));
        assertTrue(seals(geometry, 2, 0, 3, normal, right, up, 1));
        assertTrue(seals(geometry, 2, 6, 3, normal, right, up, 1));
        assertTrue(seals(geometry, 2, 3, 0, normal, right, up, 1));
        assertTrue(seals(geometry, 2, 3, 6, normal, right, up, 1));
        assertFalse(seals(geometry, 2, 3, 3, normal, right, up, 1));
    }

    @Test
    public void twoBlockShellAddsOneInnerLayerWithoutClosingTheFront() {
        LongOpenHashSet geometry = rectangularGeometry(0, 4, 0, 6, 0, 6);
        Direction normal = Direction.E;
        Direction right = Direction.S;
        Direction up = Direction.U;

        assertTrue(seals(geometry, 0, 3, 3, normal, right, up, 2));
        assertTrue(seals(geometry, 1, 3, 3, normal, right, up, 2));
        assertFalse(seals(geometry, 2, 3, 3, normal, right, up, 2));
        assertFalse(seals(geometry, 4, 3, 3, normal, right, up, 2));
        assertTrue(seals(geometry, 2, 1, 3, normal, right, up, 2));
        assertFalse(seals(geometry, 2, 2, 3, normal, right, up, 2));
    }

    @Test
    public void portalFacingSliceRemainsOpenAndSidesTaperInward() {
        LongOpenHashSet geometry = rectangularGeometry(0, 4, 0, 6, 0, 6);

        assertFalse(seals(geometry, 4, 3, 0, Direction.E, Direction.S, Direction.U, 2));
        assertFalse(seals(geometry, 4, 0, 3, Direction.E, Direction.S, Direction.U, 2));
        assertTrue(seals(geometry, 3, 3, 0, Direction.E, Direction.S, Direction.U, 2));
        assertFalse(seals(geometry, 3, 3, 1, Direction.E, Direction.S, Direction.U, 2));
        assertTrue(seals(geometry, 2, 3, 1, Direction.E, Direction.S, Direction.U, 2));
        assertFalse(seals(geometry, 2, 3, 2, Direction.E, Direction.S, Direction.U, 2));

        assertFalse(seals(geometry, 0, 3, 0, Direction.W, Direction.N, Direction.U, 2));
        assertTrue(seals(geometry, 1, 3, 0, Direction.W, Direction.N, Direction.U, 2));
        assertFalse(seals(geometry, 1, 3, 1, Direction.W, Direction.N, Direction.U, 2));
        assertTrue(seals(geometry, 2, 3, 1, Direction.W, Direction.N, Direction.U, 2));
        assertFalse(seals(geometry, 2, 3, 2, Direction.W, Direction.N, Direction.U, 2));
    }

    @Test
    public void shearedProjectionSidesFollowTheActualGeometry() {
        LongOpenHashSet geometry = new LongOpenHashSet();
        for (int x = 0; x <= 4; x++) {
            int lowZ = x - 4;
            int highZ = x + 2;
            for (int y = 0; y <= 6; y++) {
                for (int z = lowZ; z <= highZ; z++) {
                    geometry.add(ProjectionCellKey.pack(x, y, z));
                }
            }
        }

        assertTrue(seals(geometry, 2, 3, -2, Direction.E, Direction.S, Direction.U, 2));
        assertTrue(seals(geometry, 2, 3, -1, Direction.E, Direction.S, Direction.U, 2));
        assertFalse(seals(geometry, 2, 3, 0, Direction.E, Direction.S, Direction.U, 2));
        assertTrue(seals(geometry, 2, 3, 4, Direction.E, Direction.S, Direction.U, 2));
    }

    @Test
    public void farLayerReservesThePortalFacingCellInShallowVolumes() {
        assertFalse(ProjectorBlackoutSeal.isFarLayer(5, 5, 5, 1, 2));
        assertTrue(ProjectorBlackoutSeal.isFarLayer(5, 5, 6, 1, 2));
        assertFalse(ProjectorBlackoutSeal.isFarLayer(6, 5, 6, 1, 2));
        assertTrue(ProjectorBlackoutSeal.isFarLayer(6, 5, 6, -1, 2));
        assertFalse(ProjectorBlackoutSeal.isFarLayer(5, 5, 6, -1, 2));
    }

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

    private static LongOpenHashSet rectangularGeometry(int minX,
                                                       int maxX,
                                                       int minY,
                                                       int maxY,
                                                       int minZ,
                                                       int maxZ) {
        LongOpenHashSet geometry = new LongOpenHashSet();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    geometry.add(ProjectionCellKey.pack(x, y, z));
                }
            }
        }
        return geometry;
    }

    private static boolean seals(LongOpenHashSet geometry,
                                 int x,
                                 int y,
                                 int z,
                                 Direction normal,
                                 Direction right,
                                 Direction up,
                                 int thickness) {
        return ProjectorBlackoutSeal.sealsGeometryCell(
            ProjectionCellKey.pack(x, y, z), geometry, normal, right, up, 0, 4, thickness);
    }

    private static final class FakeWorldView implements ProjectionWorldView {
        private final Map<String, BlockData> blocks = new HashMap<String, BlockData>();
        private int reads;

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
            return blocks.get(key(x, y, z));
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
