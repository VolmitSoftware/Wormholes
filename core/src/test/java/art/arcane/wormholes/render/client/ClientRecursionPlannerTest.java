package art.arcane.wormholes.render.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalGeometry;
import art.arcane.wormholes.render.ProjectedBlockClaim;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import org.junit.jupiter.api.Test;

final class ClientRecursionPlannerTest {
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
    void mirrorReachCoversTheServedSideWithinDepth() {
        ClientPortalGeometry mirror = wall(0, 0, 2, true, true, List.of());
        assertTrue(ClientRecursionPlanner.mirrorReaches(mirror, new AxisAlignedBB(3, 3.999D, 64, 66.999D, -1, 1.999D)));
        assertFalse(ClientRecursionPlanner.mirrorReaches(mirror, new AxisAlignedBB(-4, -3.001D, 64, 66.999D, -1, 1.999D)),
            "behind the mirror is not reflected");
        assertFalse(ClientRecursionPlanner.mirrorReaches(mirror, new AxisAlignedBB(40, 40.999D, 64, 66.999D, -1, 1.999D)),
            "beyond the view depth");
        assertFalse(ClientRecursionPlanner.mirrorReaches(mirror, new AxisAlignedBB(3, 3.999D, 64, 66.999D, 60, 61.999D)),
            "beyond the lateral reach");
        assertFalse(ClientRecursionPlanner.mirrorReaches(wall(0, 0, 2, List.of()), new AxisAlignedBB(3, 3.999D, 64, 66.999D, -1, 1.999D)),
            "a linked portal is not a mirror");
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
            ProjectedBlockClaim.LightingPolicy.SOURCE, 0, ClientPortalGeometry.KIND_FRAME, 0, 0L, nested)).orElseThrow();
    }
}
