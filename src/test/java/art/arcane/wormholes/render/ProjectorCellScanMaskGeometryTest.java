package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.render.lod.LodPolicy;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.BukkitGeometry;
import art.arcane.wormholes.util.Cuboid;
import art.arcane.wormholes.util.Direction;

final class ProjectorCellScanMaskGeometryTest {
    private static final double DEPTH = 16.0D;

    @Test
    void aMirrorReflectingAnUnlinkedApertureMatchesColdScansWithItsCachesOn() throws ReflectiveOperationException {
        for (ProjectionRenderMode mode : new ProjectionRenderMode[] {ProjectionRenderMode.PANOPTIC, ProjectionRenderMode.VENTICULAR}) {
            Scene scene = new Scene();
            ScanRig incremental = scene.rig(false);
            Vector eye = new Vector(0.5D, 64.62D, 0.5D);
            int maskedClaims = 0;
            int emptySkips = 0;
            int maskedKeyChanges = 0;
            LongOpenHashSet previousMasks = new LongOpenHashSet();
            for (int pass = 0; pass < 28; pass++) {
                Frustum4D frustum = scene.frustum(eye);
                incremental.run(scene, eye, frustum, pass == 0, mode);
                ScanRig cold = scene.rig(true);
                cold.run(scene, eye, frustum, true, mode);
                assertEquivalentClaims(cold.scan.claims(), incremental.scan.claims(), mode + " pass=" + pass);
                LongOpenHashSet masks = new LongOpenHashSet();
                for (Long2ObjectMap.Entry<ProjectedBlockClaim<BlockData, ProjectionWorldView>> entry : incremental.scan.claims().long2ObjectEntrySet()) {
                    if (entry.getValue().isMaskAir()) {
                        masks.add(entry.getLongKey());
                    }
                }
                if (pass > 0 && !masks.equals(previousMasks)) {
                    maskedKeyChanges++;
                }
                previousMasks = masks;
                maskedClaims += masks.size();
                emptySkips += incremental.scan.emptyCellSkips();
                incremental.scan.commit();
                double angle = pass * 0.61D;
                eye.add(new Vector(Math.sin(angle) * 0.47D, Math.cos(angle * 1.7D) * 0.08D, Math.cos(angle) * 0.39D));
            }
            assertTrue(maskedClaims > 0, mode + " the reflection must cross the unlinked aperture");
            assertTrue(maskedKeyChanges > 0, mode + " the masked cells must move with the eye");
            if (mode == ProjectionRenderMode.VENTICULAR) {
                assertTrue(emptySkips > 0, mode + " mask-only recursion must keep the empty-cell cache on");
            }
        }
    }

    @Test
    void onlyTraversableRecursionMarksTheRemoteFootprintNested() throws ReflectiveOperationException {
        Scene scene = new Scene();
        Vector eye = new Vector(0.5D, 64.62D, 0.5D);
        ScanRig recursive = scene.rig(true);
        recursive.run(scene, eye, scene.frustum(eye), true, ProjectionRenderMode.PANOPTIC);
        recursive.scan.commit();
        ScanRig masked = scene.rig(false);
        masked.run(scene, eye, scene.frustum(eye), true, ProjectionRenderMode.PANOPTIC);
        masked.scan.commit();

        assertTrue(recursive.scan.remoteFootprint().nested());
        assertFalse(masked.scan.remoteFootprint().nested());
        assertTrue(masked.scan.remoteFootprint().size() > 0);
    }

    private static void assertEquivalentClaims(Long2ObjectMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> expected,
                                               Long2ObjectMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> actual,
                                               String context) {
        LongOpenHashSet liveKeys = new LongOpenHashSet(actual.size());
        for (Long2ObjectMap.Entry<ProjectedBlockClaim<BlockData, ProjectionWorldView>> entry : actual.long2ObjectEntrySet()) {
            if (entry.getValue().isHeld()) {
                assertTrue(!expected.containsKey(entry.getLongKey()), context + " held claim must be invisible from the eye");
                continue;
            }
            liveKeys.add(entry.getLongKey());
        }
        assertEquals(expected.keySet(), liveKeys, context);
        for (long key : expected.keySet()) {
            ProjectedBlockClaim<BlockData, ProjectionWorldView> expectedClaim = expected.get(key);
            ProjectedBlockClaim<BlockData, ProjectionWorldView> actualClaim = actual.get(key);
            assertEquals(expectedClaim.getData().getAsString(), actualClaim.getData().getAsString(), context);
            assertEquals(expectedClaim.getLightRemoteKey(), actualClaim.getLightRemoteKey(), context);
            assertEquals(expectedClaim.isMaskAir(), actualClaim.isMaskAir(), context);
        }
    }

    private static final class Scene {
        private final World world;
        private final ILocalPortal mirror;
        private final PortalStructure mirrorStructure;
        private final DomeWorldView view;
        private final ProjectorDestination destination;
        private final List<ILocalPortal> portals;
        private final List<ILocalPortal> portalsWithDecoy;

        private Scene() {
            world = RenderTestSupport.world("mask-geometry", List.<Entity>of());
            mirrorStructure = structure(-2, 67, -2, 2, 67, 2);
            Map<String, Object> mirrorState = portalState(world, mirrorStructure, PortalFrame.canonical(Direction.U));
            mirrorState.put("mirrorMode", Boolean.TRUE);
            mirror = RenderTestSupport.portal(mirrorState);
            PortalStructure apertureStructure = structure(5, 63, -1, 5, 65, 1);
            ILocalPortal aperture = RenderTestSupport.portal(portalState(world, apertureStructure, PortalFrame.canonical(Direction.E)));
            portals = List.of(mirror, aperture);
            Map<String, Object> decoyState = portalState(world, structure(0, 20, 0, 0, 20, 0), PortalFrame.canonical(Direction.D));
            ILocalPortal decoy = RenderTestSupport.portal(decoyState);
            decoyState.put("tunnel", RenderTestSupport.tunnel(decoy));
            portalsWithDecoy = List.of(mirror, aperture, decoy);
            view = new DomeWorldView(world);
            destination = new ProjectorDestination(mirror, ignored -> null);
            destination.dest = mirror;
            destination.destAnchor = mirror;
            destination.localView = view;
            destination.destView = view;
            destination.originX = mirrorStructure.getCenter().getX();
            destination.originY = mirrorStructure.getCenter().getY();
            destination.originZ = mirrorStructure.getCenter().getZ();
            destination.mirrorMode = true;
            destination.mirrorRotationQuarterTurns = 0;
        }

        private ScanRig rig(boolean perCellRecursion) throws ReflectiveOperationException {
            ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo(ProjectorCellScanMaskGeometryTest::occludingMaterial);
            List<ILocalPortal> candidates = perCellRecursion ? portalsWithDecoy : portals;
            ProjectorRecursivePortals<World, ILocalPortal> recursivePortals = BukkitProjectorPortalAccess.create(() -> candidates);
            ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler = withServer(
                () -> BukkitProjectorBlocks.sampler(memo, recursivePortals, ignored -> view));
            ProjectorCellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = BukkitProjectorBlocks.scan(
                mirror, sampler, memo, new ProjectorBlackoutSeal());
            Field occlusion = ProjectorCellScan.class.getDeclaredField("viewOcclusion");
            occlusion.setAccessible(true);
            occlusion.set(scan, new ProjectorViewOcclusion<BlockData>(ProjectorCellScanMaskGeometryTest::occluding));
            return new ScanRig(scan);
        }

        private Frustum4D frustum(Vector eye) {
            return new Frustum4D(BukkitGeometry.vector(eye), mirrorStructure, new Frustum4D.Options(DEPTH, 12.0D,
                Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
        }
    }

    private record ScanRig(ProjectorCellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan) {
        private void run(Scene scene, Vector eye, Frustum4D frustum, boolean cold, ProjectionRenderMode mode) {
            scan.run(scene.destination, null, BukkitGeometry.vector(eye), frustum, DEPTH, cold, false, true,
                mode.usesBuriedCellCulling(), mode, null, false, LodPolicy.NONE);
        }
    }

    private static final class DomeWorldView implements ProjectionWorldView {
        private final World world;
        private final BlockData air = blockData(Material.AIR);
        private final BlockData stone = blockData(Material.STONE);
        private final BlockData grass = blockData(Material.GRASS_BLOCK);
        private final BlockData dirt = blockData(Material.DIRT);
        private final BlockData glass = blockData(Material.GLASS);

        private DomeWorldView(World world) {
            this.world = world;
        }

        @Override
        public World getWorld() {
            return world;
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
            if (y >= 68) {
                return x >= 6 && x <= 9 && z >= -3 && z <= 3 && y >= 70 && y <= 76 ? air : stone;
            }
            if (y == 67) {
                return x >= -2 && x <= 2 && z >= -2 && z <= 2 ? air : stone;
            }
            if (y >= 63) {
                if (x == 5 && z >= -4 && z <= 4) {
                    return y <= 65 && z >= -1 && z <= 1 ? air : stone;
                }
                if (x == 3 && z == -2 && y <= 64) {
                    return glass;
                }
                return air;
            }
            if (y == 62) {
                return grass;
            }
            return y >= 59 ? dirt : stone;
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
        public boolean isChunkReady(int x, int z) {
            return true;
        }
    }

    private static PortalStructure structure(int x1, int y1, int z1, int x2, int y2, int z2) {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("worldKey", "minecraft:overworld");
        values.put("x1", Integer.valueOf(x1));
        values.put("x2", Integer.valueOf(x2));
        values.put("y1", Integer.valueOf(y1));
        values.put("y2", Integer.valueOf(y2));
        values.put("z1", Integer.valueOf(z1));
        values.put("z2", Integer.valueOf(z2));
        PortalStructure structure = new PortalStructure();
        structure.setArea(new Cuboid(values));
        return structure;
    }

    private static Map<String, Object> portalState(World world, PortalStructure structure, PortalFrame frame) {
        Vector origin = structure.getCenter().toVector();
        Map<String, Object> state = RenderTestSupport.portalState(world, origin, frame);
        state.put("structure", structure);
        state.put("view", new AxisAlignedBB(origin.getX() - 48.0D, origin.getX() + 48.0D,
            origin.getY() - 48.0D, origin.getY() + 48.0D, origin.getZ() - 48.0D, origin.getZ() + 48.0D));
        state.put("supportsProjections", Boolean.TRUE);
        state.put("projecting", Boolean.TRUE);
        state.put("open", Boolean.TRUE);
        state.put("mirrorMode", Boolean.FALSE);
        return state;
    }

    private static boolean occluding(BlockData data) {
        return data != null && occludingMaterial(data.getMaterial());
    }

    private static boolean occludingMaterial(Material material) {
        return material == Material.STONE || material == Material.GRASS_BLOCK || material == Material.DIRT;
    }

    private static BlockData blockData(Material material) {
        return (BlockData) Proxy.newProxyInstance(
            BlockData.class.getClassLoader(), new Class<?>[] {BlockData.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getMaterial" -> material;
                case "getAsString", "toString" -> material.getKey().toString();
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == args[0]);
                case "clone" -> proxy;
                default -> primitiveDefault(method.getReturnType());
            });
    }

    private static Object primitiveDefault(Class<?> returnType) {
        if (returnType == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (returnType == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        if (returnType == Long.TYPE) {
            return Long.valueOf(0L);
        }
        if (returnType == Double.TYPE) {
            return Double.valueOf(0.0D);
        }
        return null;
    }

    private static ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> withServer(SamplerFactory factory)
        throws ReflectiveOperationException {
        synchronized (Bukkit.class) {
            Field serverField = Bukkit.class.getDeclaredField("server");
            serverField.setAccessible(true);
            Object previous = serverField.get(null);
            serverField.set(null, server());
            try {
                return factory.create();
            } finally {
                serverField.set(null, previous);
            }
        }
    }

    private static Server server() {
        return (Server) Proxy.newProxyInstance(
            Server.class.getClassLoader(), new Class<?>[] {Server.class},
            (proxy, method, args) -> {
                if ("createBlockData".equals(method.getName())) {
                    return blockData(args[0] instanceof Material value ? value : Material.STONE);
                }
                return switch (method.getName()) {
                    case "getName", "toString" -> "ProjectorCellScanMaskGeometryTestServer";
                    case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                    case "equals" -> Boolean.valueOf(proxy == args[0]);
                    default -> primitiveDefault(method.getReturnType());
                };
            });
    }

    private interface SamplerFactory {
        ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> create();
    }
}
