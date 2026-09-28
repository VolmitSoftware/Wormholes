package art.arcane.wormholes.render;

import art.arcane.wormholes.render.BukkitProjectorBlocks;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.util.BukkitGeometry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.render.lod.LodPolicy;
import art.arcane.wormholes.render.plate.ViewPlate;
import art.arcane.wormholes.render.plate.ViewPlateBuilder;
import art.arcane.wormholes.render.plate.ViewPlateKey;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.util.Cuboid;
import art.arcane.wormholes.util.Direction;

public final class PortalProjectorPlateFallbackTest {
    private static final UUID PORTAL_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c3");

    @Test
    public void aNullPlateUsesTheSamplerAndAPlateYieldsIdenticalClaimsWithoutSampling() throws ReflectiveOperationException {
        for (ProjectionRenderMode renderMode : ProjectionRenderMode.values()) {
            PortalFrame frame = PortalFrame.canonical(Direction.S);
            PortalStructure structure = structure();
            ILocalPortal portal = portal(structure, frame);
            LayeredWorldView remoteView = new LayeredWorldView();
            Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
            Frustum4D frustum = new Frustum4D(BukkitGeometry.vector(eye), structure, new Frustum4D.Options(4.0D, 2.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
            boolean buried = renderMode.usesBuriedCellCulling();

            ProjectorCellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> samplerScan = scan(portal, remoteView);
            ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> samplerPath = samplerOf(samplerScan);
            samplerPath.setBuriedCellCullingPass(buried);
            samplerScan.run(destination(portal, structure, new StoneView(), remoteView), null, BukkitGeometry.vector(eye), frustum, 4.0D,
                true, false, false, buried, renderMode, null, false, LodPolicy.NONE);
            Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> samplerClaims = new Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>>(samplerScan.claims());
            assertFalse(samplerClaims.isEmpty(), renderMode.name());
            assertTrue(samplerPath.remoteSampleCount() > 0, renderMode.name());

            ViewPlate<BlockData> plate = ViewPlateBuilder.build(request(portal, structure, remoteView, buried));
            ProjectorCellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> plateScan = scan(portal, remoteView);
            ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> platePath = samplerOf(plateScan);
            platePath.setBuriedCellCullingPass(buried);
            remoteView.reads = 0;
            plateScan.run(destination(portal, structure, new StoneView(), remoteView), null, BukkitGeometry.vector(eye), frustum, 4.0D,
                true, false, false, buried, renderMode, plate, false, LodPolicy.NONE);

            assertSameClaims(samplerClaims, plateScan.claims(), renderMode.name());
            assertEquals(0, platePath.remoteSampleCount(), renderMode.name() + ": plate cells must bypass the sampler");
            assertEquals(0, remoteView.reads, renderMode.name() + ": a complete plate leaves the destination view unread");
        }
    }

    @Test
    public void cellsMissingFromThePlateFallBackToTheSampler() throws ReflectiveOperationException {
        PortalFrame frame = PortalFrame.canonical(Direction.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        LayeredWorldView remoteView = new LayeredWorldView();
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        Frustum4D frustum = new Frustum4D(BukkitGeometry.vector(eye), structure, new Frustum4D.Options(4.0D, 2.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));

        ProjectorCellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> samplerScan = scan(portal, remoteView);
        samplerScan.run(destination(portal, structure, new StoneView(), remoteView), null, BukkitGeometry.vector(eye), frustum, 4.0D,
            true, false, false, false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);
        Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> expected = new Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>>(samplerScan.claims());

        ViewPlate<BlockData> partial = ViewPlateBuilder.build(new ViewPlateBuilder.Request<BlockData, Material, ProjectionWorldView>(
            new ViewPlateKey(PORTAL_ID, remoteView, true, 0), portal.getStructure(), remoteView, frame, frame,
            structure.getCenter().getX(), structure.getCenter().getY(), structure.getCenter().getZ(),
            structure.getCenter().getX(), structure.getCenter().getY(), structure.getCenter().getZ(),
            false, 0, 1.0D, 2.0D, 0.75D, false, blockData(Material.AIR), LodPolicy.NONE, false, 0L, 1L, 0L, new BukkitProjectorBlocks(PortalProjectorPlateFallbackTest::testMaterialOccluding)));
        ProjectorCellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> plateScan = scan(portal, remoteView);
        ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> platePath = samplerOf(plateScan);
        plateScan.run(destination(portal, structure, new StoneView(), remoteView), null, BukkitGeometry.vector(eye), frustum, 4.0D,
            true, false, false, false, ProjectionRenderMode.PANOPTIC, partial, false, LodPolicy.NONE);

        assertSameClaims(expected, plateScan.claims(), "partial plate");
        assertTrue(platePath.remoteSampleCount() > 0, "cells outside the shallow plate must come from the sampler");
        assertTrue(platePath.remoteSampleCount() < expected.size(), "cells inside the plate must not be sampled");
    }

    @Test
    public void runMergingProducesTheSameCoarseViewWithAndWithoutAPlate() throws ReflectiveOperationException {
        PortalFrame frame = PortalFrame.canonical(Direction.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        LayeredWorldView remoteView = new LayeredWorldView();
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        Frustum4D frustum = new Frustum4D(BukkitGeometry.vector(eye), structure, new Frustum4D.Options(6.0D, 2.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
        LodPolicy lod = new LodPolicy(true, 1, 3);

        ProjectorCellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> samplerScan = scan(portal, remoteView);
        ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> samplerPath = samplerOf(samplerScan);
        samplerScan.run(destination(portal, structure, new StoneView(), remoteView), null, BukkitGeometry.vector(eye), frustum, 6.0D,
            true, false, false, false, ProjectionRenderMode.PANOPTIC, null, false, lod);
        Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> coarse = new Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>>(samplerScan.claims());
        int coarseReads = remoteView.reads;

        ProjectorCellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> denseScan = scan(portal, remoteView);
        remoteView.reads = 0;
        denseScan.run(destination(portal, structure, new StoneView(), remoteView), null, BukkitGeometry.vector(eye), frustum, 6.0D,
            true, false, false, false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);
        assertTrue(coarseReads < remoteView.reads, "run merging must read fewer destination cells than the dense scan");
        assertTrue(samplerPath.remoteSampleCount() > 0);

        ViewPlateBuilder.Request<BlockData, Material, ProjectionWorldView> request = new ViewPlateBuilder.Request<BlockData, Material, ProjectionWorldView>(new ViewPlateKey(PORTAL_ID, remoteView, true, 0),
            portal.getStructure(), remoteView, frame, frame,
            structure.getCenter().getX(), structure.getCenter().getY(), structure.getCenter().getZ(),
            structure.getCenter().getX(), structure.getCenter().getY(), structure.getCenter().getZ(),
            false, 0, 6.0D, 2.0D, 0.75D, false, blockData(Material.AIR), lod, false, 0L, 1L, 0L, new BukkitProjectorBlocks(PortalProjectorPlateFallbackTest::testMaterialOccluding));
        ViewPlate<BlockData> plate = ViewPlateBuilder.build(request);
        ProjectorCellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> plateScan = scan(portal, remoteView);
        plateScan.run(destination(portal, structure, new StoneView(), remoteView), null, BukkitGeometry.vector(eye), frustum, 6.0D,
            true, false, false, false, ProjectionRenderMode.PANOPTIC, plate, false, lod);
        assertSameClaims(coarse, plateScan.claims(), "lod plate");
        assertEquals(0, samplerOf(plateScan).remoteSampleCount());
    }

    private static void assertSameClaims(Long2ObjectMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> expected,
                                         Long2ObjectMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> actual,
                                         String label) {
        assertEquals(new LongOpenHashSet(expected.keySet()), new LongOpenHashSet(actual.keySet()), label);
        for (Long2ObjectMap.Entry<ProjectedBlockClaim<BlockData, ProjectionWorldView>> entry : expected.long2ObjectEntrySet()) {
            ProjectedBlockClaim<BlockData, ProjectionWorldView> left = entry.getValue();
            ProjectedBlockClaim<BlockData, ProjectionWorldView> right = actual.get(entry.getLongKey());
            assertEquals(left.getData().getMaterial(), right.getData().getMaterial(), label);
            assertEquals(left.getLightRemoteKey(), right.getLightRemoteKey(), label);
            assertEquals(left.isMaskAir(), right.isMaskAir(), label);
            assertEquals(left.getLightingPolicy(), right.getLightingPolicy(), label);
        }
    }

    private static ViewPlateBuilder.Request<BlockData, Material, ProjectionWorldView> request(ILocalPortal portal, PortalStructure structure,
                                                    ProjectionWorldView remoteView, boolean buried) {
        PortalFrame frame = portal.getFrame();
        return new ViewPlateBuilder.Request<BlockData, Material, ProjectionWorldView>(new ViewPlateKey(PORTAL_ID, remoteView, true, 0), portal.getStructure(), remoteView, frame, frame,
            structure.getCenter().getX(), structure.getCenter().getY(), structure.getCenter().getZ(),
            structure.getCenter().getX(), structure.getCenter().getY(), structure.getCenter().getZ(),
            false, 0, 4.0D, 2.0D, 0.75D, buried, blockData(Material.AIR), LodPolicy.NONE, false, 0L, 1L, 0L, new BukkitProjectorBlocks(PortalProjectorPlateFallbackTest::testMaterialOccluding));
    }

    private static ProjectorCellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan(ILocalPortal portal, ProjectionWorldView remoteView) throws ReflectiveOperationException {
        ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo(PortalProjectorPlateFallbackTest::testMaterialOccluding);
        ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler = withBukkitServer(
            () -> BukkitProjectorBlocks.sampler(memo, BukkitProjectorPortalAccess.create(), world -> remoteView));
        ProjectorCellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = BukkitProjectorBlocks.scan(portal, sampler, memo, new ProjectorBlackoutSeal());
        Field field = ProjectorCellScan.class.getDeclaredField("viewOcclusion");
        field.setAccessible(true);
        field.set(scan, new ProjectorViewOcclusion<BlockData>(PortalProjectorPlateFallbackTest::testOccluding));
        return scan;
    }

    private static ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> samplerOf(ProjectorCellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan) throws ReflectiveOperationException {
        Field field = ProjectorCellScan.class.getDeclaredField("sampler");
        field.setAccessible(true);
        return (ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView>) field.get(scan);
    }

    private static boolean testOccluding(BlockData data) {
        return data != null && testMaterialOccluding(data.getMaterial());
    }

    private static boolean testMaterialOccluding(Material material) {
        return material == Material.STONE || material == Material.GRASS_BLOCK
            || material == Material.DIRT || material == Material.DEEPSLATE;
    }

    private static ProjectorDestination destination(ILocalPortal portal, PortalStructure structure,
                                                    ProjectionWorldView localView, ProjectionWorldView remoteView) {
        ProjectorDestination destination = new ProjectorDestination(portal, world -> null);
        destination.dest = portal;
        destination.destAnchor = portal;
        destination.localView = localView;
        destination.destView = remoteView;
        destination.originX = structure.getCenter().getX();
        destination.originY = structure.getCenter().getY();
        destination.originZ = structure.getCenter().getZ();
        destination.mirrorMode = false;
        destination.mirrorRotationQuarterTurns = 0;
        return destination;
    }

    private static PortalStructure structure() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("worldKey", "minecraft:overworld");
        values.put("x1", Integer.valueOf(0));
        values.put("x2", Integer.valueOf(0));
        values.put("y1", Integer.valueOf(64));
        values.put("y2", Integer.valueOf(65));
        values.put("z1", Integer.valueOf(0));
        values.put("z2", Integer.valueOf(0));
        PortalStructure structure = new PortalStructure();
        structure.setArea(new Cuboid(values));
        return structure;
    }

    private static ILocalPortal portal(PortalStructure structure, PortalFrame frame) {
        Vector origin = structure.getCenter().toVector();
        return (ILocalPortal) Proxy.newProxyInstance(
            ILocalPortal.class.getClassLoader(), new Class<?>[] {ILocalPortal.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getStructure" -> structure;
                case "getFrame" -> frame;
                case "getOrigin" -> BukkitGeometry.vector(origin);
                case "getId" -> PORTAL_ID;
                case "getWorld" -> null;
                case "getName", "toString" -> "fallback-portal";
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == args[0]);
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

    private static ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> withBukkitServer(SamplerFactory factory) throws ReflectiveOperationException {
        synchronized (Bukkit.class) {
            Field serverField = Bukkit.class.getDeclaredField("server");
            serverField.setAccessible(true);
            Object previous = serverField.get(null);
            serverField.set(null, fakeServer());
            try {
                return factory.create();
            } finally {
                serverField.set(null, previous);
            }
        }
    }

    private static Server fakeServer() {
        return (Server) Proxy.newProxyInstance(
            Server.class.getClassLoader(), new Class<?>[] {Server.class},
            (proxy, method, args) -> {
                if ("createBlockData".equals(method.getName())) {
                    Material material = args[0] instanceof Material value ? value : Material.STONE;
                    return blockData(material);
                }
                return switch (method.getName()) {
                    case "getName", "toString" -> "PortalProjectorPlateFallbackTestServer";
                    case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                    case "equals" -> Boolean.valueOf(proxy == args[0]);
                    default -> primitiveDefault(method.getReturnType());
                };
            });
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

    private interface SamplerFactory {
        ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> create();
    }

    private static final class StoneView implements ProjectionWorldView {
        private final BlockData stone = blockData(Material.STONE);

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
            return stone;
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

    private static final class LayeredWorldView implements ProjectionWorldView {
        private final BlockData air = blockData(Material.AIR);
        private final BlockData grass = blockData(Material.GRASS_BLOCK);
        private final BlockData foliage = blockData(Material.SHORT_GRASS);
        private final BlockData dirt = blockData(Material.DIRT);
        private final BlockData deep = blockData(Material.DEEPSLATE);
        private final BlockData glass = blockData(Material.GLASS);
        int reads;

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
            if (y == 64) {
                return (x + z) % 3 == 0 ? glass : grass;
            }
            if (y == 65) {
                return (x & 1) == 0 ? foliage : air;
            }
            if (y == 63) {
                return dirt;
            }
            if (y <= 62) {
                return deep;
            }
            return air;
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
}
