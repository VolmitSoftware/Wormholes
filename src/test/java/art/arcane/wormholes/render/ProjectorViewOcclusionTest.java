package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.network.view.ViewBox;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.render.view.RemoteWorldView;
import art.arcane.wormholes.util.Direction;

public final class ProjectorViewOcclusionTest {
    @Test
    public void flatOpaqueWallCullsEverythingBehindItsVisibleSurface() {
        FakeWorldView view = new FakeWorldView();
        fillPlane(view, 2, -2, 4, -2, 4, Material.STONE);
        ProjectorViewOcclusion occlusion = occlusion();
        beginPass(occlusion);

        assertTrue(occlusion.visible(view, 2, 1, 1, 0.5D, 1.5D, 1.5D));
        assertFalse(occlusion.visible(view, 4, 1, 1, 0.5D, 1.5D, 1.5D));
        assertFalse(occlusion.visible(view, 7, 3, -1, 0.5D, 1.5D, 1.5D));
        assertFalse(view.wasRead(4, 1, 1), "visibility checks must not sample hidden target geometry");
    }

    @Test
    public void caveWallsCullRockBehindThemButLeaveTheOpeningVisible() {
        FakeWorldView sealed = new FakeWorldView();
        fillPlane(sealed, 3, -3, 4, -3, 4, Material.DEEPSLATE);
        ProjectorViewOcclusion occlusion = occlusion();
        beginPass(occlusion);

        assertFalse(occlusion.visible(sealed, 6, 0, 0, 0.5D, 0.5D, 0.5D));

        FakeWorldView opening = new FakeWorldView();
        fillPlane(opening, 3, -3, 4, -3, 4, Material.DEEPSLATE);
        opening.put(3, 0, 0, Material.AIR);
        beginPass(occlusion);

        assertTrue(occlusion.visible(opening, 6, 0, 0, 0.5D, 0.5D, 0.5D));
    }

    @Test
    public void anObliqueFlatWallStillCullsTheFullyCoveredTarget() {
        FakeWorldView view = new FakeWorldView();
        view.put(2, 2, 0, Material.STONE);
        view.put(2, 3, 0, Material.STONE);
        ProjectorViewOcclusion occlusion = occlusion();
        beginPass(occlusion);

        assertFalse(occlusion.visible(view, 4, 6, 0, 0.5D, 0.5D, 0.5D));
    }

    @Test
    public void steppedHillWithoutAnExactCoverageProofFailsOpen() {
        FakeWorldView view = new FakeWorldView();
        view.put(3, 0, 0, Material.STONE);
        view.put(2, 1, 0, Material.STONE);
        ProjectorViewOcclusion occlusion = occlusion();
        beginPass(occlusion);

        assertTrue(occlusion.visible(view, 6, 1, 0, 0.5D, 0.5D, 0.5D));

        FakeWorldView opening = new FakeWorldView();
        opening.put(3, 0, 0, Material.STONE);
        beginPass(occlusion);
        assertTrue(occlusion.visible(opening, 6, 1, 0, 0.5D, 0.5D, 0.5D));
    }

    @Test
    public void glassWaterAndPartialBlocksDoNotHideGeometry() {
        Material[] transparent = new Material[] { Material.GLASS, Material.WATER, Material.OAK_SLAB };
        for (Material material : transparent) {
            FakeWorldView view = new FakeWorldView();
            view.put(2, 1, 1, material);
            ProjectorViewOcclusion occlusion = occlusion();
            beginPass(occlusion);

            assertTrue(occlusion.visible(view, 5, 1, 1, 0.5D, 1.5D, 1.5D), material.name());
        }
    }

    @Test
    public void anExposedCornerKeepsAPartiallyVisibleBlock() {
        FakeWorldView view = new FakeWorldView();
        view.put(2, 1, 1, Material.STONE);
        ProjectorViewOcclusion occlusion = occlusion();
        beginPass(occlusion);

        assertTrue(occlusion.visible(view, 4, 2, 1, 0.5D, 1.5D, 1.5D));
    }

    @Test
    public void unsampledFaceGapKeepsAPartiallyVisibleOpaqueTarget() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet blockers = new LongOpenHashSet();
        int[][] coordinates = new int[][] {{3, 1, 0}, {4, 0, 1}, {4, 1, 1}};
        for (int[] coordinate : coordinates) {
            view.put(coordinate[0], coordinate[1], coordinate[2], Material.STONE);
            blockers.add(ProjectionCellKey.pack(coordinate[0], coordinate[1], coordinate[2]));
        }
        ProjectorViewOcclusion occlusion = occlusion();
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Direction.W, blockers);

        assertTrue(occlusion.visible(view, 6, 0, 0, 0.5D, 2.3D, 2.1D));
    }

    @Test
    public void blockerOutsideTheAcceptedProjectionSetCannotHideATarget() {
        FakeWorldView view = new FakeWorldView();
        view.put(2, 1, 1, Material.STONE);
        LongOpenHashSet accepted = new LongOpenHashSet();
        ProjectorViewOcclusion occlusion = occlusion();
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Direction.W, accepted);

        assertTrue(occlusion.visible(view, 5, 1, 1, 0.5D, 1.5D, 1.5D));

        accepted.add(ProjectionCellKey.pack(2, 1, 1));
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Direction.W, accepted);
        assertFalse(occlusion.visible(view, 5, 1, 1, 0.5D, 1.5D, 1.5D));
        assertFalse(view.wasRead(2, 1, 1));
    }

    @Test
    public void completeSameSlabEligibilityRemovesTraversalOrderDependence() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet priorSlabOnly = new LongOpenHashSet();
        LongOpenHashSet complete = new LongOpenHashSet();
        for (int x = 1; x <= 2; x++) {
            for (int z = -1; z <= 1; z++) {
                view.put(x, 3, z, Material.STONE);
                complete.add(ProjectionCellKey.pack(x, 3, z));
                if (x == 1) {
                    priorSlabOnly.add(ProjectionCellKey.pack(x, 3, z));
                }
            }
        }
        ProjectorViewOcclusion occlusion = occlusion();
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Direction.W, priorSlabOnly);

        assertTrue(occlusion.visible(view, 2, 2, 0, 0.5D, 8.5D, 0.5D));

        occlusion.beginPass(0.5D, 0.5D, 0.5D, Direction.W, complete);
        assertFalse(occlusion.visible(view, 2, 2, 0, 0.5D, 8.5D, 0.5D));
    }

    @Test
    public void opaqueTargetWithNoExposedEyeFacingFaceIsCulled() {
        FakeWorldView view = new FakeWorldView();
        view.put(3, 1, 1, Material.STONE);
        view.put(4, 0, 1, Material.STONE);
        view.put(4, 2, 1, Material.STONE);
        view.put(4, 1, 0, Material.STONE);
        view.put(4, 1, 2, Material.STONE);
        ProjectorViewOcclusion occlusion = occlusion();
        beginPass(occlusion);

        assertFalse(occlusion.visible(view, 4, 1, 1, 0.5D, 1.5D, 1.5D));
    }

    @Test
    public void aGrazingCaveWallFaceIsNotEclipsed() {
        FakeWorldView view = new FakeWorldView();
        view.put(2, 1, 1, Material.STONE);
        ProjectorViewOcclusion occlusion = occlusion();
        beginPass(occlusion);

        assertTrue(occlusion.visible(view, 4, 2, 1, 0.5D, 1.35D, 1.5D));
    }

    @Test
    public void fartherBlockOnTheSameFloorPlaneRemainsVisible() {
        FakeWorldView view = new FakeWorldView();
        for (int x = 1; x <= 8; x++) {
            for (int z = -2; z <= 2; z++) {
                view.put(x, 0, z, Material.STONE);
            }
        }
        ProjectorViewOcclusion occlusion = occlusion();
        beginPass(occlusion);

        assertTrue(occlusion.visible(view, 4, 0, 0, 0.5D, 1.5D, 0.5D));
    }

    @Test
    public void fartherBlockOnTheSameWallPlaneRemainsVisible() {
        FakeWorldView view = new FakeWorldView();
        for (int x = 1; x <= 8; x++) {
            for (int y = -1; y <= 3; y++) {
                view.put(x, y, 0, Material.STONE);
            }
        }
        ProjectorViewOcclusion occlusion = occlusion();
        beginPass(occlusion);

        assertTrue(occlusion.visible(view, 4, 1, 0, 0.5D, 1.5D, 1.5D));
    }

    @Test
    public void frontSideTerrainIsIgnoredButBehindPortalTerrainStillOccludes() {
        FakeWorldView view = new FakeWorldView();
        for (int x = 1; x <= 4; x++) {
            view.put(x, 0, 0, Material.STONE);
        }
        ProjectorViewOcclusion occlusion = occlusion();
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Direction.E);

        assertTrue(occlusion.visible(view, -4, 0, 0, 4.5D, 0.5D, 0.5D));

        view.put(-2, 0, 0, Material.STONE);
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Direction.E);
        assertFalse(occlusion.visible(view, -4, 0, 0, 4.5D, 0.5D, 0.5D));
    }

    @Test
    public void firstVoxelBehindEitherPortalFaceOccludesDeeperGeometry() {
        FakeWorldView positive = new FakeWorldView();
        positive.put(1, 0, 0, Material.STONE);
        ProjectorViewOcclusion occlusion = occlusion();
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Direction.W);

        assertFalse(occlusion.visible(positive, 3, 0, 0, 0.5D, 0.5D, 0.5D));

        FakeWorldView negative = new FakeWorldView();
        negative.put(-1, 0, 0, Material.STONE);
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Direction.E);

        assertFalse(occlusion.visible(negative, -3, 0, 0, 0.5D, 0.5D, 0.5D));
    }

    @Test
    public void eligibleBlockerOctreeMatchesDenseWorldSampling() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet blockers = new LongOpenHashSet();
        fillPlane(view, 2, -8, 8, -8, 8, Material.STONE);
        for (int y = -8; y <= 8; y++) {
            for (int z = -8; z <= 8; z++) {
                blockers.add(ProjectionCellKey.pack(2, y, z));
            }
        }
        ProjectorViewOcclusion occlusion = occlusion();
        occlusion.beginPass(0.5D, 1.5D, 1.5D, Direction.W, blockers);

        assertTrue(occlusion.visible(view, 2, 1, 1, 0.5D, 1.5D, 1.5D));
        assertFalse(occlusion.visible(view, 20, 1, 1, 0.5D, 1.5D, 1.5D));
        assertFalse(occlusion.visible(view, 40, 3, -4, 0.5D, 1.5D, 1.5D));
    }

    @Test
    public void unavailableSnapshotCellFailsOpenAndRequestsCapture() {
        FakeWorldView view = new FakeWorldView();
        view.unknown(2, 1, 1);
        ProjectorViewOcclusion occlusion = occlusion();
        beginPass(occlusion);

        assertTrue(occlusion.visible(view, 5, 1, 1, 0.5D, 1.5D, 1.5D));
        assertTrue(view.wasRequested(2, 1));
        assertFalse(view.wasRead(5, 1, 1));
    }

    @Test
    public void unavailableRemoteSliceCellFailsOpenWithoutLeakingTargetSample() throws Exception {
        RemoteViewCache cache = new RemoteViewCache();
        RemoteViewCache.RemoteView cached = cache.getOrCreate("peer", UUID.randomUUID());
        Field box = RemoteViewCache.RemoteView.class.getDeclaredField("box");
        box.setAccessible(true);
        box.set(cached, new ViewBox(0, -64, 0, 15, 319, 15));
        RemoteWorldView remoteView = new RemoteWorldView(cached, blockData(Material.AIR));
        ProjectorViewOcclusion occlusion = occlusion();
        beginPass(occlusion);

        assertTrue(occlusion.visible(remoteView, 7, 0, 0, 0.5D, 0.5D, 0.5D));
    }

    @Test
    public void aRevisionChangeDuringThePassFailsOpen() {
        FakeWorldView view = new FakeWorldView();
        view.put(2, 1, 1, Material.STONE);
        ProjectorViewOcclusion occlusion = occlusion();
        beginPass(occlusion);

        assertFalse(occlusion.visible(view, 5, 1, 1, 0.5D, 1.5D, 1.5D));
        view.revision++;
        assertTrue(occlusion.visible(view, 5, 1, 1, 0.5D, 1.5D, 1.5D));
    }

    @Test
    public void largeVolumeWorkAndRetainedCacheAreStrictlyBounded() {
        FakeWorldView view = new FakeWorldView();
        view.fillXPlane = 2;
        ProjectorViewOcclusion occlusion = occlusion();
        beginPass(occlusion);
        int candidates = 250_000;
        int hidden = 0;

        for (int i = 0; i < candidates; i++) {
            if (!occlusion.visible(view, 4, 0, 0, 0.5D, 0.5D, 0.5D)) {
                hidden++;
            }
        }

        assertEquals(candidates, hidden, "the flat-wall fast path must stay inside the pass budget");
        assertTrue(occlusion.voxelSteps() <= candidates * 2,
            "the center-blocker coverage fast path must use at most two voxel steps per candidate");
        assertTrue(occlusion.voxelSteps() <= ProjectorViewOcclusion.MAX_VOXEL_STEPS_PER_PASS);
        assertFalse(occlusion.budgetExhausted());
        assertTrue(occlusion.opacityCacheSize() <= ProjectorViewOcclusion.MAX_OPACITY_CACHE_CELLS);
    }

    @Test
    public void opacityMemoNeverRetainsMoreThanItsFixedCellCap() {
        FakeWorldView view = new FakeWorldView();
        ProjectorViewOcclusion occlusion = occlusion();
        beginPass(occlusion);

        for (int i = 0; i < ProjectorViewOcclusion.MAX_OPACITY_CACHE_CELLS + 1_000; i++) {
            int baseX = i * 3;
            assertTrue(occlusion.visible(view, baseX + 2, 0, 0, baseX + 0.5D, 0.5D, 0.5D));
        }

        assertEquals(ProjectorViewOcclusion.MAX_OPACITY_CACHE_CELLS, occlusion.opacityCacheSize());
    }

    @Test
    public void exhaustedBudgetFailsOpenAndNeverExceedsTheHardStepLimit() {
        FakeWorldView view = new FakeWorldView();
        ProjectorViewOcclusion occlusion = occlusion();
        beginPass(occlusion);

        boolean visible = false;
        for (int i = 0; i < 100_000 && !occlusion.budgetExhausted(); i++) {
            visible = occlusion.visible(view, 300, i & 3, 0, 0.5D, 0.5D, 0.5D);
        }

        assertTrue(occlusion.budgetExhausted());
        assertTrue(visible);
        assertEquals(ProjectorViewOcclusion.MAX_VOXEL_STEPS_PER_PASS, occlusion.voxelSteps());
    }

    private static void fillPlane(FakeWorldView view, int x, int minY, int maxY, int minZ, int maxZ, Material material) {
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                view.put(x, y, z, material);
            }
        }
    }

    private static ProjectorViewOcclusion occlusion() {
        return new ProjectorViewOcclusion(data -> data != null
            && (data.getMaterial() == Material.STONE || data.getMaterial() == Material.DEEPSLATE));
    }

    private static void beginPass(ProjectorViewOcclusion occlusion) {
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Direction.W);
    }

    private static BlockData blockData(Material material) {
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[] { BlockData.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "getMaterial" -> material;
                case "toString", "getAsString" -> material.name();
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == args[0]);
                case "clone" -> proxy;
                default -> null;
            });
    }

    private static final class FakeWorldView implements ProjectionWorldView {
        private final Map<String, BlockData> blocks = new HashMap<String, BlockData>();
        private final Map<String, Integer> reads = new HashMap<String, Integer>();
        private final Map<String, Boolean> unknown = new HashMap<String, Boolean>();
        private final Map<String, Boolean> requested = new HashMap<String, Boolean>();
        private final BlockData air = blockData(Material.AIR);
        private int fillXPlane = Integer.MIN_VALUE;
        private long revision;

        private void put(int x, int y, int z, Material material) {
            blocks.put(key(x, y, z), blockData(material));
        }

        private void unknown(int x, int y, int z) {
            unknown.put(key(x, y, z), Boolean.TRUE);
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
            String key = key(x, y, z);
            reads.merge(key, Integer.valueOf(1), Integer::sum);
            if (unknown.containsKey(key)) {
                return null;
            }
            if (x == fillXPlane) {
                return blockData(Material.STONE);
            }
            return blocks.getOrDefault(key, air);
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

        @Override
        public long getRevision() {
            return revision;
        }

        @Override
        public void requestChunk(int x, int z) {
            requested.put(x + ":" + z, Boolean.TRUE);
        }

        private boolean wasRead(int x, int y, int z) {
            return reads.containsKey(key(x, y, z));
        }

        private boolean wasRequested(int x, int z) {
            return requested.containsKey(x + ":" + z);
        }

        private static String key(int x, int y, int z) {
            return x + ":" + y + ":" + z;
        }
    }
}
