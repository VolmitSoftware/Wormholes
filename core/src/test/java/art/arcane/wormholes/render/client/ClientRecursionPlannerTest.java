package art.arcane.wormholes.render.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.wormholes.door.DoorwayPlane;
import java.util.List;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.portal.PortalGeometry;
import art.arcane.wormholes.render.ProjectedBlockClaim;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import org.junit.jupiter.api.Test;

final class ClientRecursionPlannerTest {
    @Test
    void nativeReachUsesRotatedDestinationSpaceAndTheFullMeshDistance() {
        ClientPortalGeometry root = wall(0, 0, 3, List.of()).withDepth(128);
        ClientViewEnvironment.Transform transform = new ClientViewEnvironment.Transform(Direction.S, Direction.U, Direction.W,
            new GeometryVector(100, 0, -20));
        assertTrue(ClientRecursionPlanner.destinationReaches(root, transform, new AxisAlignedBB(19, 21, 64, 67, 180, 181)));
        assertFalse(ClientRecursionPlanner.destinationReaches(root, transform, new AxisAlignedBB(19, 21, 64, 67, 20, 21)));
        assertFalse(ClientRecursionPlanner.destinationReaches(root, transform, new AxisAlignedBB(19, 21, 64, 67, 260, 261)));
        ClientViewEnvironment.Transform reflection = new ClientViewEnvironment.Transform(Direction.W, Direction.U, Direction.S,
            new GeometryVector(1, 0, 0));
        assertTrue(ClientRecursionPlanner.destinationReaches(root, reflection, new AxisAlignedBB(4, 5, 64, 67, 0, 1)));
        assertFalse(ClientRecursionPlanner.destinationReaches(root, reflection, new AxisAlignedBB(-5, -4, 64, 67, 0, 1)));
    }

    @Test
    void aRootWithoutNestedPortalsPlansNothing() {
        ClientPortalGeometry root = wall(0, 0, 3, List.of());
        assertTrue(new ClientRecursionPlanner(4).plan(root, 6.5D, 65.5D, 0.5D).isEmpty());
    }

    @Test
    void nestedConesFollowTheChainUpToTheDepthLimit() {
        ClientPortalGeometry great = wall(-30, 0, 0, List.of());
        ClientPortalGeometry grand = wall(-20, 0, 1, List.of(great));
        ClientPortalGeometry child = wall(-10, 0, 2, List.of(grand));
        ClientPortalGeometry root = wall(0, 0, 2, List.of(child));
        List<ClientRecursionPlanner.NestedCone> cones = new ClientRecursionPlanner(8).plan(root, 6.5D, 65.5D, 0.5D);
        assertEquals(2, cones.size());
        assertSame(child, cones.get(0).geometry());
        assertEquals(1, cones.get(0).depth());
        assertEquals(List.of(root), cones.get(0).ancestors());
        assertSame(grand, cones.get(1).geometry());
        assertEquals(2, cones.get(1).depth());
        assertEquals(List.of(root, child), cones.get(1).ancestors());
        assertEquals(1, new ClientRecursionPlanner(1).plan(root, 6.5D, 65.5D, 0.5D).size());
    }

    @Test
    void nestedPortalsOutsideTheParentWindowAreSkipped() {
        ClientPortalGeometry hidden = wall(-10, 40, 0, List.of());
        ClientPortalGeometry visible = wall(-10, 0, 0, List.of());
        ClientPortalGeometry root = wall(0, 0, 2, List.of(hidden, visible));
        List<ClientRecursionPlanner.NestedCone> cones = new ClientRecursionPlanner(4).plan(root, 6.5D, 65.5D, 0.5D);
        assertEquals(1, cones.size());
        assertSame(visible, cones.get(0).geometry());
        ClientRecursionPlanner.NestedCone cone = cones.get(0);
        assertTrue(cone.visible(-12.5D, 65.5D, 0.5D));
        assertFalse(cone.visible(-12.5D, 65.5D, 30.5D));
        assertTrue(cone.space().identity());
        assertEquals(6.5D, cone.contentEyeX());
    }

    @Test
    void portalsInsideAMirrorArePlannedInTheMirrorSourceSpace() {
        ClientPortalGeometry child = wall(3, 0, 0, false, false, List.of());
        ClientPortalGeometry mirror = wall(0, 0, 2, true, true, List.of(child));
        List<ClientRecursionPlanner.NestedCone> cones = new ClientRecursionPlanner(4).plan(mirror, 6.5D, 65.5D, 0.5D);
        assertEquals(1, cones.size());
        ClientRecursionPlanner.NestedCone cone = cones.get(0);
        assertSame(child, cone.geometry());
        assertEquals(List.of(mirror), cone.space().reflections());
        assertTrue(cone.contentEyeX() < 0.0D, "the eye is reflected behind the mirror");
        assertEquals(65.5D, cone.contentEyeY(), 1.0E-9D);
        assertTrue(cone.visible(-6.5D, 65.5D, 0.5D));
        assertFalse(cone.visible(-6.5D, 65.5D, 30.5D));
        int[] cell = new int[3];
        double[] scratch = new double[3];
        cone.space().contentCell(-6, 65, 0, cell, scratch);
        assertEquals(6, cell[0]);
        assertEquals(65, cell[1]);
        assertEquals(0, cell[2]);
        cone.space().displayCell(6, 65, 0, cell, scratch);
        assertEquals(-6, cell[0]);
    }

    @Test
    void aMirroredChildFacingTheRealEyeIsNotPlanned() {
        ClientPortalGeometry child = wall(3, 0, 0, true, false, List.of());
        ClientPortalGeometry mirror = wall(0, 0, 2, true, true, List.of(child));
        assertTrue(new ClientRecursionPlanner(4).plan(mirror, 6.5D, 65.5D, 0.5D).isEmpty());
    }

    @Test
    void mirrorDestinationReachCoversTheServedSideWithinDepth() {
        ClientPortalGeometry mirror = wall(0, 0, 2, true, true, List.of());
        ClientViewEnvironment.Transform reflection = new ClientViewEnvironment.Transform(Direction.W, Direction.U, Direction.S,
            new GeometryVector(1, 0, 0));
        assertTrue(ClientRecursionPlanner.destinationReaches(mirror, reflection, new AxisAlignedBB(3, 3.999D, 64, 66.999D, -1, 1.999D)));
        assertFalse(ClientRecursionPlanner.destinationReaches(mirror, reflection, new AxisAlignedBB(-4, -3.001D, 64, 66.999D, -1, 1.999D)));
        assertFalse(ClientRecursionPlanner.destinationReaches(mirror, reflection, new AxisAlignedBB(40, 40.999D, 64, 66.999D, -1, 1.999D)));
        assertFalse(ClientRecursionPlanner.destinationReaches(mirror, reflection, new AxisAlignedBB(3, 3.999D, 64, 66.999D, 60, 61.999D)));
    }

    @Test
    void childRecursionDepthBoundsItsDescendants() {
        ClientPortalGeometry great = wall(-30, 0, 0, List.of());
        ClientPortalGeometry grand = wall(-20, 0, 3, List.of(great));
        ClientPortalGeometry child = wall(-10, 0, 1, List.of(grand));
        ClientPortalGeometry root = wall(0, 0, 4, List.of(child));
        List<ClientRecursionPlanner.NestedCone> cones = new ClientRecursionPlanner(8).plan(root, 6.5D, 65.5D, 0.5D);
        assertEquals(2, cones.size());
        assertSame(child, cones.get(0).geometry());
        assertSame(grand, cones.get(1).geometry());
        ClientPortalGeometry leaf = wall(-10, 0, 0, List.of(grand));
        assertEquals(1, new ClientRecursionPlanner(8).plan(root.withNested(List.of(leaf)), 6.5D, 65.5D, 0.5D).size());
    }

    @Test
    void mixedSurfaceKindsUseTheSameReachAndNestedWindows() {
        ClientViewEnvironment.Transform destination = new ClientViewEnvironment.Transform(Direction.E, Direction.U, Direction.S,
            new GeometryVector(-100, 0, 0));
        AxisAlignedBB visible = new AxisAlignedBB(89, 90, 64, 67, -1, 2);
        AxisAlignedBB behind = new AxisAlignedBB(110, 111, 64, 67, -1, 2);
        for (int kind : new int[]{ClientPortalGeometry.KIND_FRAME, ClientPortalGeometry.KIND_RTP,
            ClientPortalGeometry.KIND_DOOR, ClientPortalGeometry.KIND_VANILLA_REPLACEMENT}) {
            ClientPortalGeometry reflection = withKind(wall(-10, 0, 1, true, true, List.of()), ClientPortalGeometry.KIND_FRAME);
            ClientPortalGeometry doorway = withKind(wall(0, 0, 3, List.of(reflection)), kind);
            assertTrue(ClientRecursionPlanner.destinationReaches(doorway, destination, visible));
            assertFalse(ClientRecursionPlanner.destinationReaches(doorway, destination, behind));
            List<ClientRecursionPlanner.NestedCone> cones = new ClientRecursionPlanner(8).plan(doorway, 6.5D, 65.5D, 0.5D);
            assertEquals(1, cones.size());
            assertSame(reflection, cones.getFirst().geometry());
            assertTrue(cones.getFirst().visible(-12.5D, 65.5D, 0.5D));
        }
        ClientPortalGeometry door = withKind(wall(3, 0, 0, false, false, List.of()), ClientPortalGeometry.KIND_DOOR);
        ClientPortalGeometry mirror = wall(0, 0, 2, true, true, List.of(door));
        List<ClientRecursionPlanner.NestedCone> reflected = new ClientRecursionPlanner(8).plan(mirror, 6.5D, 65.5D, 0.5D);
        assertEquals(1, reflected.size());
        assertSame(door, reflected.getFirst().geometry());
        assertTrue(reflected.getFirst().contentEyeX() < 0.0D);
    }

    private static ClientPortalGeometry withKind(ClientPortalGeometry geometry, int kind) {
        return new ClientPortalGeometry(geometry.originX(), geometry.originY(), geometry.originZ(), geometry.facing(),
            geometry.frontSide(), geometry.quarterTurns(), geometry.mirror(), geometry.apertureWidth(), geometry.apertureHeight(),
            geometry.apertureMask(), geometry.nearPlanePadding(), geometry.aperturePadding(), geometry.frustumCullingRatio(),
            geometry.depthBlocks(), geometry.recursionDepth(), geometry.blackoutPolicy(), geometry.blackoutState(),
            geometry.maskAirPolicy(), geometry.lightingPolicy(), geometry.fidelityFlags(), kind,
            kind == ClientPortalGeometry.KIND_DOOR ? DoorwayPlane.planeOffset(geometry.facingDirection()) : 0.0D, geometry.parentPortalKey(),
            geometry.targetIdentity(), geometry.nested());
    }

    private static ClientPortalGeometry wall(int x, int z, int recursionDepth, List<ClientPortalGeometry> nested) {
        return wall(x, z, recursionDepth, true, false, nested);
    }

    private static ClientPortalGeometry wall(int x, int z, int recursionDepth, boolean frontSide, boolean mirror,
                                             List<ClientPortalGeometry> nested) {
        PortalGeometry aperture = new PortalGeometry();
        aperture.setArea(new AxisAlignedBB(x, x + 0.999D, 64.0D, 66.999D, z - 1.0D, z + 1.999D));
        return ClientPortalGeometry.fromPortal(new ClientPortalGeometry.Source(aperture, PortalFrame.canonical(Direction.E), frontSide, mirror, 0,
            2.0D, 0.75D, 0.2D, 32, recursionDepth, ClientPortalGeometry.BLACKOUT_OFF, 0, ClientPortalGeometry.MASK_AIR_PROJECT,
            ProjectedBlockClaim.LightingPolicy.SOURCE, 0, ClientPortalGeometry.KIND_FRAME, 0.0D, 0, 0L, nested)).orElseThrow();
    }
}
