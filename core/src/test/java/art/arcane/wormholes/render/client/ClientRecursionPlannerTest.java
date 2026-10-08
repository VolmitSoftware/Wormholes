package art.arcane.wormholes.render.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.wormholes.door.DoorwayPlane;
import java.util.List;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.claim.BlockClaim;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import org.junit.jupiter.api.Test;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.recursion.ClientRecursionPlanner;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.portal.ApertureKind;

final class ClientRecursionPlannerTest {
    @Test
    void nativeReachUsesRotatedDestinationSpaceAndTheFullMeshDistance() {
        ApertureDescriptor root = wall(0, 0, 3, List.of()).withDepth(128);
        OpticTransform transform = OpticTransform.of(AxisPermutation.of(Face.S, Face.U, Face.W), 100, 0, -20);
        assertTrue(ClientRecursionPlanner.destinationReaches(root, transform, new Box(19, 21, 64, 67, 180, 181)));
        assertFalse(ClientRecursionPlanner.destinationReaches(root, transform, new Box(19, 21, 64, 67, 20, 21)));
        assertFalse(ClientRecursionPlanner.destinationReaches(root, transform, new Box(19, 21, 64, 67, 260, 261)));
        OpticTransform reflection = OpticTransform.of(AxisPermutation.of(Face.W, Face.U, Face.S), 1, 0, 0);
        assertTrue(ClientRecursionPlanner.destinationReaches(root, reflection, new Box(4, 5, 64, 67, 0, 1)));
        assertFalse(ClientRecursionPlanner.destinationReaches(root, reflection, new Box(-5, -4, 64, 67, 0, 1)));
    }

    @Test
    void aRootWithoutNestedPortalsPlansNothing() {
        ApertureDescriptor root = wall(0, 0, 3, List.of());
        assertTrue(new ClientRecursionPlanner(4).plan(root, 6.5D, 65.5D, 0.5D).isEmpty());
    }

    @Test
    void nestedConesFollowTheChainUpToTheDepthLimit() {
        ApertureDescriptor great = wall(-30, 0, 0, List.of());
        ApertureDescriptor grand = wall(-20, 0, 1, List.of(great));
        ApertureDescriptor child = wall(-10, 0, 2, List.of(grand));
        ApertureDescriptor root = wall(0, 0, 2, List.of(child));
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
        ApertureDescriptor hidden = wall(-10, 40, 0, List.of());
        ApertureDescriptor visible = wall(-10, 0, 0, List.of());
        ApertureDescriptor root = wall(0, 0, 2, List.of(hidden, visible));
        List<ClientRecursionPlanner.NestedCone> cones = new ClientRecursionPlanner(4).plan(root, 6.5D, 65.5D, 0.5D);
        assertEquals(1, cones.size());
        assertSame(visible, cones.get(0).geometry());
        ClientRecursionPlanner.NestedCone cone = cones.get(0);
        assertTrue(cone.visible(-12.5D, 65.5D, 0.5D));
        assertFalse(cone.visible(-12.5D, 65.5D, 30.5D));
        assertTrue(cone.reflections().isEmpty());
        assertTrue(cone.transform().isIdentity());
        assertEquals(6.5D, cone.contentEyeX());
    }

    @Test
    void portalsInsideAMirrorArePlannedInTheMirrorSourceSpace() {
        ApertureDescriptor child = wall(3, 0, 0, false, false, List.of());
        ApertureDescriptor mirror = wall(0, 0, 2, true, true, List.of(child));
        List<ClientRecursionPlanner.NestedCone> cones = new ClientRecursionPlanner(4).plan(mirror, 6.5D, 65.5D, 0.5D);
        assertEquals(1, cones.size());
        ClientRecursionPlanner.NestedCone cone = cones.get(0);
        assertSame(child, cone.geometry());
        assertEquals(List.of(mirror), cone.reflections());
        assertTrue(cone.contentEyeX() < 0.0D, "the eye is reflected behind the mirror");
        assertEquals(65.5D, cone.contentEyeY(), 1.0E-9D);
        assertTrue(cone.visible(-6.5D, 65.5D, 0.5D));
        assertFalse(cone.visible(-6.5D, 65.5D, 30.5D));
        int[] cell = new int[3];
        cone.transform().inverse().cellInto(-6, 65, 0, cell);
        assertEquals(6, cell[0]);
        assertEquals(65, cell[1]);
        assertEquals(0, cell[2]);
        cone.transform().cellInto(6, 65, 0, cell);
        assertEquals(-6, cell[0]);
    }

    @Test
    void aMirroredChildFacingTheRealEyeIsNotPlanned() {
        ApertureDescriptor child = wall(3, 0, 0, true, false, List.of());
        ApertureDescriptor mirror = wall(0, 0, 2, true, true, List.of(child));
        assertTrue(new ClientRecursionPlanner(4).plan(mirror, 6.5D, 65.5D, 0.5D).isEmpty());
    }

    @Test
    void mirrorDestinationReachCoversTheServedSideWithinDepth() {
        ApertureDescriptor mirror = wall(0, 0, 2, true, true, List.of());
        OpticTransform reflection = OpticTransform.of(AxisPermutation.of(Face.W, Face.U, Face.S), 1, 0, 0);
        assertTrue(ClientRecursionPlanner.destinationReaches(mirror, reflection, new Box(3, 3.999D, 64, 66.999D, -1, 1.999D)));
        assertFalse(ClientRecursionPlanner.destinationReaches(mirror, reflection, new Box(-4, -3.001D, 64, 66.999D, -1, 1.999D)));
        assertFalse(ClientRecursionPlanner.destinationReaches(mirror, reflection, new Box(40, 40.999D, 64, 66.999D, -1, 1.999D)));
        assertFalse(ClientRecursionPlanner.destinationReaches(mirror, reflection, new Box(3, 3.999D, 64, 66.999D, 60, 61.999D)));
    }

    @Test
    void childRecursionDepthBoundsItsDescendants() {
        ApertureDescriptor great = wall(-30, 0, 0, List.of());
        ApertureDescriptor grand = wall(-20, 0, 3, List.of(great));
        ApertureDescriptor child = wall(-10, 0, 1, List.of(grand));
        ApertureDescriptor root = wall(0, 0, 4, List.of(child));
        List<ClientRecursionPlanner.NestedCone> cones = new ClientRecursionPlanner(8).plan(root, 6.5D, 65.5D, 0.5D);
        assertEquals(2, cones.size());
        assertSame(child, cones.get(0).geometry());
        assertSame(grand, cones.get(1).geometry());
        ApertureDescriptor leaf = wall(-10, 0, 0, List.of(grand));
        assertEquals(1, new ClientRecursionPlanner(8).plan(root.withNested(List.of(leaf)), 6.5D, 65.5D, 0.5D).size());
    }

    @Test
    void mixedSurfaceKindsUseTheSameReachAndNestedWindows() {
        OpticTransform destination = OpticTransform.of(AxisPermutation.of(Face.E, Face.U, Face.S), -100, 0, 0);
        Box visible = new Box(89, 90, 64, 67, -1, 2);
        Box behind = new Box(110, 111, 64, 67, -1, 2);
        for (int kind : new int[]{ApertureKind.FRAME, ApertureKind.RTP,
            ApertureKind.DOOR, ApertureKind.VANILLA_REPLACEMENT}) {
            ApertureDescriptor reflection = withKind(wall(-10, 0, 1, true, true, List.of()), ApertureKind.FRAME);
            ApertureDescriptor doorway = withKind(wall(0, 0, 3, List.of(reflection)), kind);
            assertTrue(ClientRecursionPlanner.destinationReaches(doorway, destination, visible));
            assertFalse(ClientRecursionPlanner.destinationReaches(doorway, destination, behind));
            List<ClientRecursionPlanner.NestedCone> cones = new ClientRecursionPlanner(8).plan(doorway, 6.5D, 65.5D, 0.5D);
            assertEquals(1, cones.size());
            assertSame(reflection, cones.getFirst().geometry());
            assertTrue(cones.getFirst().visible(-12.5D, 65.5D, 0.5D));
        }
        ApertureDescriptor door = withKind(wall(3, 0, 0, false, false, List.of()), ApertureKind.DOOR);
        ApertureDescriptor mirror = wall(0, 0, 2, true, true, List.of(door));
        List<ClientRecursionPlanner.NestedCone> reflected = new ClientRecursionPlanner(8).plan(mirror, 6.5D, 65.5D, 0.5D);
        assertEquals(1, reflected.size());
        assertSame(door, reflected.getFirst().geometry());
        assertTrue(reflected.getFirst().contentEyeX() < 0.0D);
    }

    private static ApertureDescriptor withKind(ApertureDescriptor geometry, int kind) {
        return new ApertureDescriptor(geometry.originX(), geometry.originY(), geometry.originZ(), geometry.facing(),
            geometry.frontSide(), geometry.quarterTurns(), geometry.mirror(), geometry.apertureWidth(), geometry.apertureHeight(),
            geometry.apertureMask(), ShapeDescriptor.FULL, geometry.nearPlanePadding(), geometry.aperturePadding(), geometry.frustumCullingRatio(),
            geometry.depthBlocks(), geometry.recursionDepth(), geometry.blackoutPolicy(), geometry.blackoutState(),
            geometry.maskAirPolicy(), geometry.lightingPolicy(), geometry.fidelityFlags(), kind,
            kind == ApertureKind.DOOR ? DoorwayPlane.planeOffset(geometry.facingDirection()) : 0.0D, geometry.parentPortalKey(),
            geometry.targetIdentity(), geometry.nested());
    }

    private static ApertureDescriptor wall(int x, int z, int recursionDepth, List<ApertureDescriptor> nested) {
        return wall(x, z, recursionDepth, true, false, nested);
    }

    private static ApertureDescriptor wall(int x, int z, int recursionDepth, boolean frontSide, boolean mirror,
                                             List<ApertureDescriptor> nested) {
        ApertureCells aperture = new ApertureCells();
        aperture.setArea(new Box(x, x + 0.999D, 64.0D, 66.999D, z - 1.0D, z + 1.999D));
        return ApertureDescriptor.fromPortal(new ApertureDescriptor.Source(aperture, Frame.canonical(Face.E), frontSide, mirror, 0,
            2.0D, 0.75D, 0.2D, 32, recursionDepth, ApertureDescriptor.BLACKOUT_OFF, 0, ApertureDescriptor.MASK_AIR_PROJECT,
            BlockClaim.LightingPolicy.SOURCE, 0, ApertureKind.FRAME, 0.0D, 0, 0L, ShapeDescriptor.FULL, nested)).orElseThrow();
    }
}
