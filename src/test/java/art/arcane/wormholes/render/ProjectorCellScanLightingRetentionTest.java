package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

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
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.util.Cuboid;
import art.arcane.wormholes.util.Direction;

public final class ProjectorCellScanLightingRetentionTest {
    @Test
    public void unavailableLocalAndRemoteChunksFollowCurrentBlackoutLighting() throws ReflectiveOperationException {
        PortalFrame frame = PortalFrame.canonical(Direction.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.STONE));
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo memo = new ProjectorSampleMemo(
            ProjectorCellScanLightingRetentionTest::testMaterialOccluding);
        ProjectorSampler sampler = withBukkitServer(
            () -> new ProjectorSampler(memo, new ProjectorRecursivePortals(), world -> remoteView));
        ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
        ProjectorCellScan scan = new ProjectorCellScan(portal, sampler, memo, blackout);
        useOcclusion(scan, ProjectorCellScanLightingRetentionTest::testOccluding);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        Frustum4D frustum = new Frustum4D(eye, structure, 4.0D, 2.0D);

        scan.run(destination, null, eye, frustum, 4.0D, true, false, false, ProjectionRenderMode.PANOPTIC);

        assertFalse(scan.claims().isEmpty());
        assertLighting(scan, ProjectedBlockClaim.LightingPolicy.SOURCE);
        LongOpenHashSet initialKeys = new LongOpenHashSet(scan.claims().keySet());
        scan.commit();

        enableBlackout(blackout);
        localView.ready = false;
        scan.run(destination, null, eye, frustum, 4.0D, true, false, false, ProjectionRenderMode.PANOPTIC);

        assertEquals(initialKeys, scan.claims().keySet());
        assertLighting(scan, ProjectedBlockClaim.LightingPolicy.FULL_BRIGHT);
        assertTrue(localView.requests > 0);
        scan.commit();

        blackout.disable();
        localView.ready = true;
        remoteView.ready = false;
        remoteView.data = null;
        remoteView.reads = 0;
        memo.clearDestinationSamples();
        scan.run(destination, null, eye, frustum, 4.0D, true, false, false, ProjectionRenderMode.PANOPTIC);

        assertEquals(initialKeys, scan.claims().keySet());
        assertLighting(scan, ProjectedBlockClaim.LightingPolicy.SOURCE);
        assertTrue(remoteView.reads > 0);
        assertEquals(0, remoteView.requests);
    }

    @Test
    public void venticularKeepsDestinationSurfaceAndBackingMaterialOverLocalStone() throws ReflectiveOperationException {
        PortalFrame frame = PortalFrame.canonical(Direction.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
        LayeredWorldView remoteView = new LayeredWorldView();
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo memo = new ProjectorSampleMemo(
            ProjectorCellScanLightingRetentionTest::testMaterialOccluding);
        ProjectorSampler sampler = withBukkitServer(
            () -> new ProjectorSampler(memo, new ProjectorRecursivePortals(), world -> remoteView));
        ProjectorCellScan scan = new ProjectorCellScan(portal, sampler, memo, new ProjectorBlackoutSeal());
        useOcclusion(scan, ProjectorCellScanLightingRetentionTest::testOccluding);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        Frustum4D frustum = new Frustum4D(eye, structure, 4.0D, 2.0D);

        scan.run(destination, null, eye, frustum, 4.0D, true, false, true, ProjectionRenderMode.VENTICULAR);

        int grassClaims = 0;
        int foliageClaims = 0;
        int backingClaims = 0;
        int deepClaims = 0;
        for (ProjectedBlockClaim claim : scan.claims().values()) {
            Material material = claim.getData().getMaterial();
            if (material == Material.GRASS_BLOCK) {
                grassClaims++;
            } else if (material == Material.SHORT_GRASS) {
                foliageClaims++;
            } else if (material == Material.DIRT) {
                backingClaims++;
            } else if (material == Material.DEEPSLATE) {
                deepClaims++;
            }
            assertFalse(material == Material.STONE);
        }
        assertTrue(grassClaims >= 3, "grassClaims=" + grassClaims);
        assertTrue(foliageClaims >= 4, "foliageClaims=" + foliageClaims);
        assertTrue(backingClaims >= 3, "backingClaims=" + backingClaims);
        assertEquals(0, deepClaims, "depth-two interior must stay culled");
    }

    @Test
    public void blackoutMasksOnlyTransparentCellsOnTheFarthestProjectionSlab()
        throws ReflectiveOperationException {
        PortalFrame frame = PortalFrame.canonical(Direction.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        Frustum4D frustum = new Frustum4D(eye, structure, 4.0D, 2.0D);
        int expectedFarZ = PortalProjector.minBlockForCenter(frustum.getRegion().getZa());

        for (ProjectionRenderMode renderMode : ProjectionRenderMode.values()) {
            MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
            TransparentSkylineWorldView remoteView = new TransparentSkylineWorldView(expectedFarZ);
            ProjectorDestination destination = destination(portal, structure, localView, remoteView);
            ProjectorSampleMemo memo = new ProjectorSampleMemo(
                ProjectorCellScanLightingRetentionTest::testMaterialOccluding);
            ProjectorSampler sampler = withBukkitServer(
                () -> new ProjectorSampler(memo, new ProjectorRecursivePortals(), world -> remoteView));
            ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
            enableBlackout(blackout);
            ProjectorCellScan scan = new ProjectorCellScan(portal, sampler, memo, blackout);
            useOcclusion(scan, ProjectorCellScanLightingRetentionTest::testOccluding);
            boolean buriedCellCulling = renderMode.usesBuriedCellCulling();
            sampler.setBuriedCellCullingPass(buriedCellCulling);

            scan.run(destination, null, eye, frustum, 4.0D, true, false,
                buriedCellCulling, renderMode);

            LongOpenHashSet geometry = blackoutGeometry(scan);
            assertFalse(geometry.isEmpty(), renderMode.name());
            assertEquals(expectedFarZ, minimumBlackoutGeometryZ(scan), renderMode.name());
            Vector portalOrigin = structure.getCenter().toVector();
            PortalFrame projectionFrame = scan.localFrame();
            ProjectorPlaneWindow exactWindow = ProjectorPlaneWindow.create(
                structure, structure.getArea(), projectionFrame,
                portalOrigin.getX(), portalOrigin.getY(), portalOrigin.getZ(), 0.0D, scan.eyeDot());
            Direction projectionNormal = projectionFrame.getNormal();
            LongOpenHashSet expectedGeometry = new LongOpenHashSet();
            boolean foundOpaque = false;
            boolean foundGlass = false;
            boolean foundWater = false;
            boolean foundAir = false;
            boolean foundNearTransparent = false;
            for (Long2ObjectMap.Entry<ProjectedBlockClaim> entry : scan.claims().long2ObjectEntrySet()) {
                long key = entry.getLongKey();
                Material material = entry.getValue().getData().getMaterial();
                boolean transparent = !testOccluding(entry.getValue().getData());
                if (ProjectionCellKey.unpackZ(key) == expectedFarZ) {
                    int x = ProjectionCellKey.unpackX(key);
                    int y = ProjectionCellKey.unpackY(key);
                    int z = ProjectionCellKey.unpackZ(key);
                    double cx = x + 0.5D;
                    double cy = y + 0.5D;
                    double cz = z + 0.5D;
                    double cellSignedDistance = ((cx - portalOrigin.getX()) * projectionNormal.x())
                        + ((cy - portalOrigin.getY()) * projectionNormal.y())
                        + ((cz - portalOrigin.getZ()) * projectionNormal.z());
                    boolean exactAperture = exactWindow.containsRayIntersection(
                        eye.getX(), eye.getY(), eye.getZ(), cx, cy, cz, cellSignedDistance);
                    if (transparent && exactAperture) {
                        expectedGeometry.add(key);
                    }
                    foundOpaque |= !transparent && exactAperture;
                    foundGlass |= material == Material.GLASS && exactAperture;
                    foundWater |= material == Material.WATER && exactAperture;
                    foundAir |= material == Material.AIR && exactAperture;
                } else if (transparent) {
                    foundNearTransparent = true;
                    assertFalse(geometry.contains(key), material + " " + renderMode.name());
                }
            }
            assertTrue(foundOpaque, renderMode.name());
            assertTrue(foundGlass, renderMode.name());
            assertTrue(foundWater, renderMode.name());
            assertTrue(foundAir, renderMode.name());
            assertTrue(foundNearTransparent, renderMode.name());
            assertEquals(expectedGeometry, geometry, renderMode.name());
            for (long key : geometry) {
                assertEquals(expectedFarZ, ProjectionCellKey.unpackZ(key), renderMode.name());
            }
            assertEquals(geometry.size(), scan.blackoutMesh().panels().stream()
                .mapToInt(panel -> panel.uSize() * panel.vSize())
                .sum(), renderMode.name());
            assertTrue(scan.blackoutMesh().panels().stream()
                .allMatch(panel -> panel.axis() == 2), renderMode.name());
        }
    }

    @Test
    public void blackoutIncludesFarAirWhenTheLocalCellIsAlreadyAir() throws ReflectiveOperationException {
        PortalFrame frame = PortalFrame.canonical(Direction.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.AIR));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.AIR));
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo memo = new ProjectorSampleMemo();
        ProjectorSampler sampler = withBukkitServer(
            () -> new ProjectorSampler(memo, new ProjectorRecursivePortals(), world -> remoteView));
        ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
        enableBlackout(blackout);
        ProjectorCellScan scan = new ProjectorCellScan(portal, sampler, memo, blackout);
        useOcclusion(scan, ProjectorCellScanLightingRetentionTest::testOccluding);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        Frustum4D frustum = new Frustum4D(eye, structure, 4.0D, 2.0D);

        scan.run(destination, null, eye, frustum, 4.0D, true, false, false, ProjectionRenderMode.PANOPTIC);

        assertTrue(scan.claims().isEmpty());
        LongOpenHashSet initialMask = new LongOpenHashSet(blackoutGeometry(scan));
        assertFalse(initialMask.isEmpty());
        assertEquals(PortalProjector.minBlockForCenter(frustum.getRegion().getZa()),
            minimumBlackoutGeometryZ(scan));
        assertFalse(scan.blackoutMesh().panels().isEmpty());
        scan.commit();

        localView.ready = false;
        scan.run(destination, null, eye, frustum, 4.0D, true, false, false, ProjectionRenderMode.PANOPTIC);

        assertEquals(initialMask, blackoutGeometry(scan));
        assertFalse(scan.blackoutMesh().panels().isEmpty());
        scan.commit();

        localView.ready = true;
        remoteView.data = blockData(Material.STONE);
        memo.clearDestinationSamples();
        scan.run(destination, null, eye, frustum, 4.0D, true, false, false, ProjectionRenderMode.PANOPTIC);

        assertTrue(blackoutGeometry(scan).isEmpty());
        assertTrue(scan.blackoutMesh().panels().isEmpty());
    }

    @Test
    public void fullyOpaqueFarBoundaryCreatesNoBlackout() throws ReflectiveOperationException {
        PortalFrame frame = PortalFrame.canonical(Direction.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.STONE));
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo memo = new ProjectorSampleMemo();
        ProjectorSampler sampler = withBukkitServer(
            () -> new ProjectorSampler(memo, new ProjectorRecursivePortals(), world -> remoteView));
        ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
        enableBlackout(blackout);
        ProjectorCellScan scan = new ProjectorCellScan(portal, sampler, memo, blackout);
        useOcclusion(scan, ProjectorCellScanLightingRetentionTest::testOccluding);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        Frustum4D frustum = new Frustum4D(eye, structure, 4.0D, 2.0D);

        scan.run(destination, null, eye, frustum, 4.0D, true, false, false, ProjectionRenderMode.PANOPTIC);

        assertFalse(scan.claims().isEmpty());
        assertTrue(blackoutGeometry(scan).isEmpty());
        assertTrue(scan.blackoutMesh().panels().isEmpty());
        assertFalse(scan.blackoutMesh().fallback());
    }

    @Test
    public void blackoutUsesTheDeepestProjectionSlabForEveryNormal()
        throws ReflectiveOperationException {
        for (Direction normal : Direction.values()) {
            PortalFrame frame = PortalFrame.canonical(normal);
            PortalStructure structure = orientedStructure(frame);
            ILocalPortal portal = portal(structure, frame);
            MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
            MutableWorldView remoteView = new MutableWorldView(blockData(Material.AIR));
            ProjectorDestination destination = destination(portal, structure, localView, remoteView);
            ProjectorSampleMemo memo = new ProjectorSampleMemo();
            ProjectorSampler sampler = withBukkitServer(
                () -> new ProjectorSampler(memo, new ProjectorRecursivePortals(), world -> remoteView));
            ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
            enableBlackout(blackout);
            ProjectorCellScan scan = new ProjectorCellScan(portal, sampler, memo, blackout);
            useOcclusion(scan, ProjectorCellScanLightingRetentionTest::testOccluding);
            Location eye = structure.getCenter().add(
                normal.x() * 1.5D, normal.y() * 1.5D, normal.z() * 1.5D);
            Frustum4D frustum = new Frustum4D(eye, structure, 4.0D, 2.0D);
            int expectedCoordinate = farFrustumCoordinate(frustum, normal);

            scan.run(destination, null, eye, frustum, 4.0D, true, false,
                false, ProjectionRenderMode.PANOPTIC);

            LongOpenHashSet geometry = blackoutGeometry(scan);
            assertFalse(geometry.isEmpty(), normal.name()
                + " planeRejected=" + scan.planeRejected()
                + " windowRejected=" + scan.windowRejected()
                + " frustumRejected=" + scan.frustumRejected()
                + " region=" + frustum.getRegion());
            for (long key : geometry) {
                assertEquals(expectedCoordinate, coordinate(key, normal), normal.name());
            }
            int expectedAxis = normal.x() != 0 ? 0 : normal.y() != 0 ? 1 : 2;
            assertTrue(scan.blackoutMesh().panels().stream()
                .allMatch(panel -> panel.axis() == expectedAxis), normal.name());
        }
    }

    @Test
    public void unavailableMaskDoesNotCrossDestinationOrCoordinateMappings()
        throws ReflectiveOperationException {
        PortalFrame frame = PortalFrame.canonical(Direction.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        Frustum4D frustum = new Frustum4D(eye, structure, 4.0D, 2.0D);

        for (int scenario = 0; scenario < 2; scenario++) {
            MutableWorldView localView = new MutableWorldView(blockData(Material.AIR));
            MutableWorldView remoteView = new MutableWorldView(blockData(Material.AIR));
            ProjectorDestination destination = destination(portal, structure, localView, remoteView);
            ProjectorSampleMemo memo = new ProjectorSampleMemo();
            ProjectorSampler sampler = withBukkitServer(
                () -> new ProjectorSampler(memo, new ProjectorRecursivePortals(), world -> remoteView));
            ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
            enableBlackout(blackout);
            ProjectorCellScan scan = new ProjectorCellScan(portal, sampler, memo, blackout);
            useOcclusion(scan, ProjectorCellScanLightingRetentionTest::testOccluding);

            scan.run(destination, null, eye, frustum, 4.0D, true, false,
                false, ProjectionRenderMode.PANOPTIC);
            assertFalse(blackoutGeometry(scan).isEmpty());
            scan.commit();
            localView.ready = false;
            if (scenario == 0) {
                destination.originX += 1.0D;
            } else {
                MutableWorldView replacementView = new MutableWorldView(null);
                replacementView.ready = false;
                destination.destView = replacementView;
            }

            scan.run(destination, null, eye, frustum, 4.0D, true, false,
                false, ProjectionRenderMode.PANOPTIC);

            assertTrue(blackoutGeometry(scan).isEmpty(), "scenario=" + scenario);
            assertTrue(scan.blackoutMesh().panels().isEmpty(), "scenario=" + scenario);
        }
    }

    @Test
    public void blackoutDisplayFailureFailsOpenWithoutCreatingConcreteClaims()
        throws ReflectiveOperationException {
        PortalFrame frame = PortalFrame.canonical(Direction.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.AIR));
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo memo = new ProjectorSampleMemo();
        ProjectorSampler sampler = withBukkitServer(
            () -> new ProjectorSampler(memo, new ProjectorRecursivePortals(), world -> remoteView));
        ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
        enableBlackout(blackout);
        ProjectorCellScan scan = new ProjectorCellScan(portal, sampler, memo, blackout);
        useOcclusion(scan, ProjectorCellScanLightingRetentionTest::testOccluding);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        Frustum4D frustum = new Frustum4D(eye, structure, 4.0D, 2.0D);

        scan.run(destination, null, eye, frustum, 4.0D, true, false, false, ProjectionRenderMode.PANOPTIC);
        Long2ObjectMap<ProjectedBlockClaim> projectionClaims =
            new Long2ObjectOpenHashMap<ProjectedBlockClaim>(scan.claims());
        assertFalse(scan.blackoutMesh().panels().isEmpty());

        scan.dropBlackoutDisplay();

        assertEquals(projectionClaims, scan.claims());
        assertTrue(scan.blackoutMesh().fallback());
        assertTrue(scan.blackoutMesh().panels().isEmpty());
        assertFalse(scan.claims().isEmpty());
        for (ProjectedBlockClaim claim : scan.claims().values()) {
            assertFalse(claim.getData().getMaterial() == Material.BLACK_CONCRETE);
        }
    }

    private static void useOcclusion(ProjectorCellScan scan,
                                     ProjectorViewOcclusion.BlockOcclusion blockOcclusion)
        throws ReflectiveOperationException {
        Field field = ProjectorCellScan.class.getDeclaredField("viewOcclusion");
        field.setAccessible(true);
        field.set(scan, new ProjectorViewOcclusion(blockOcclusion));
    }

    private static int minimumBlackoutGeometryZ(ProjectorCellScan scan) throws ReflectiveOperationException {
        LongOpenHashSet geometry = blackoutGeometry(scan);
        int minimum = Integer.MAX_VALUE;
        for (long key : geometry) {
            minimum = Math.min(minimum, ProjectionCellKey.unpackZ(key));
        }
        return minimum;
    }

    private static LongOpenHashSet blackoutGeometry(ProjectorCellScan scan) throws ReflectiveOperationException {
        Field field = ProjectorCellScan.class.getDeclaredField("blackoutGeometry");
        field.setAccessible(true);
        return (LongOpenHashSet) field.get(scan);
    }

    private static int farFrustumCoordinate(Frustum4D frustum, Direction normal) {
        if (normal.x() > 0) {
            return PortalProjector.minBlockForCenter(frustum.getRegion().getXa());
        }
        if (normal.x() < 0) {
            return PortalProjector.maxBlockForCenter(frustum.getRegion().getXb());
        }
        if (normal.y() > 0) {
            return PortalProjector.minBlockForCenter(frustum.getRegion().getYa());
        }
        if (normal.y() < 0) {
            return PortalProjector.maxBlockForCenter(frustum.getRegion().getYb());
        }
        return normal.z() > 0
            ? PortalProjector.minBlockForCenter(frustum.getRegion().getZa())
            : PortalProjector.maxBlockForCenter(frustum.getRegion().getZb());
    }

    private static int coordinate(long key, Direction direction) {
        if (direction.x() != 0) {
            return ProjectionCellKey.unpackX(key);
        }
        if (direction.y() != 0) {
            return ProjectionCellKey.unpackY(key);
        }
        return ProjectionCellKey.unpackZ(key);
    }

    private static boolean testOccluding(BlockData data) {
        if (data == null) {
            return false;
        }
        Material material = data.getMaterial();
        return material == Material.STONE
            || material == Material.GRASS_BLOCK
            || material == Material.DIRT
            || material == Material.DEEPSLATE;
    }

    private static boolean testMaterialOccluding(Material material) {
        return material == Material.STONE
            || material == Material.GRASS_BLOCK
            || material == Material.DIRT
            || material == Material.DEEPSLATE;
    }

    private static ProjectorDestination destination(ILocalPortal portal,
                                                    PortalStructure structure,
                                                    ProjectionWorldView localView,
                                                    ProjectionWorldView remoteView) {
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

    private static void assertLighting(ProjectorCellScan scan, ProjectedBlockClaim.LightingPolicy expected) {
        for (ProjectedBlockClaim claim : scan.claims().values()) {
            assertEquals(expected, claim.getLightingPolicy());
        }
    }

    private static void enableBlackout(ProjectorBlackoutSeal blackout) throws ReflectiveOperationException {
        Field enabled = ProjectorBlackoutSeal.class.getDeclaredField("enabled");
        enabled.setAccessible(true);
        enabled.setBoolean(blackout, true);
        Field data = ProjectorBlackoutSeal.class.getDeclaredField("blackoutData");
        data.setAccessible(true);
        data.set(blackout, blockData(Material.BLACK_CONCRETE));
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

    private static PortalStructure orientedStructure(PortalFrame frame) {
        Direction up = frame.getUp();
        Direction right = frame.getRight();
        int x = up.x() + right.x();
        int y = up.y() + right.y();
        int z = up.z() + right.z();
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("worldKey", "minecraft:overworld");
        values.put("x1", Integer.valueOf(Math.min(0, x)));
        values.put("x2", Integer.valueOf(Math.max(0, x)));
        values.put("y1", Integer.valueOf(64 + Math.min(0, y)));
        values.put("y2", Integer.valueOf(64 + Math.max(0, y)));
        values.put("z1", Integer.valueOf(Math.min(0, z)));
        values.put("z2", Integer.valueOf(Math.max(0, z)));
        PortalStructure structure = new PortalStructure();
        structure.setArea(new Cuboid(values));
        return structure;
    }

    private static ILocalPortal portal(PortalStructure structure, PortalFrame frame) {
        Vector origin = structure.getCenter().toVector();
        return (ILocalPortal) Proxy.newProxyInstance(
            ILocalPortal.class.getClassLoader(), new Class<?>[] { ILocalPortal.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "getStructure" -> structure;
                case "getFrame" -> frame;
                case "getOrigin" -> origin;
                case "getWorld" -> null;
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

    private static ProjectorSampler withBukkitServer(SamplerFactory factory) throws ReflectiveOperationException {
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
            Server.class.getClassLoader(), new Class<?>[] { Server.class },
            (proxy, method, args) -> {
                if ("createBlockData".equals(method.getName())) {
                    Material material = args[0] instanceof Material value ? value : Material.STONE;
                    return blockData(material);
                }
                return switch (method.getName()) {
                    case "getName", "toString" -> "ProjectorCellScanLightingRetentionTestServer";
                    case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                    case "equals" -> Boolean.valueOf(proxy == args[0]);
                    default -> primitiveDefault(method.getReturnType());
                };
            });
    }

    private static BlockData blockData(Material material) {
        return (BlockData) Proxy.newProxyInstance(
            BlockData.class.getClassLoader(), new Class<?>[] { BlockData.class },
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
        ProjectorSampler create();
    }

    private static final class MutableWorldView implements ProjectionWorldView {
        private BlockData data;
        private boolean ready;
        private int reads;
        private int requests;

        private MutableWorldView(BlockData data) {
            this.data = data;
            this.ready = true;
            this.reads = 0;
            this.requests = 0;
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
            return data;
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
            return ready;
        }

        @Override
        public void requestChunk(int x, int z) {
            requests++;
        }
    }

    private static final class LayeredWorldView implements ProjectionWorldView {
        private final BlockData air = blockData(Material.AIR);
        private final BlockData grass = blockData(Material.GRASS_BLOCK);
        private final BlockData foliage = blockData(Material.SHORT_GRASS);
        private final BlockData dirt = blockData(Material.DIRT);
        private final BlockData deep = blockData(Material.DEEPSLATE);

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
            if (y == 64) {
                return grass;
            }
            if (y == 65) {
                return foliage;
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

        @Override
        public boolean isChunkReady(int x, int z) {
            return true;
        }
    }

    private static final class TransparentSkylineWorldView implements ProjectionWorldView {
        private final BlockData air = blockData(Material.AIR);
        private final BlockData glass = blockData(Material.GLASS);
        private final BlockData water = blockData(Material.WATER);
        private final BlockData grass = blockData(Material.GRASS_BLOCK);
        private final BlockData stone = blockData(Material.STONE);
        private final int farZ;

        private TransparentSkylineWorldView(int farZ) {
            this.farZ = farZ;
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
            if (x < 0) {
                return grass;
            }
            if (x == 0) {
                return (y & 1) == 0 ? glass : water;
            }
            if (x == 2 && z == farZ) {
                return stone;
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

        @Override
        public boolean isChunkReady(int x, int z) {
            return true;
        }
    }
}
