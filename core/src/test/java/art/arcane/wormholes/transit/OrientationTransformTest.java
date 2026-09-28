package art.arcane.wormholes.transit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import art.arcane.wormholes.geometry.GeometryVector;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalCrossing;
import art.arcane.wormholes.util.Direction;

/**
 * Expected vectors are computed by hand from the canonical triads:
 * N(normal N, up U, right E), S(S, U, W), E(E, U, S), W(W, U, N), U(U, up S, right E), D(D, up N, right E).
 * The entry look (0, 0.6, -0.8) through canonical(N) has components right 0, up 0.6, normal 0.8.
 */
final class OrientationTransformTest {
    private static final double EPSILON = 1e-9D;
    private static final GeometryVector ENTRY_LOOK = new GeometryVector(0.0D, 0.6D, -0.8D);

    private static final Map<Direction, GeometryVector> FRAME = Map.of(
        Direction.N, new GeometryVector(0.0D, 0.6D, -0.8D),
        Direction.S, new GeometryVector(0.0D, 0.6D, 0.8D),
        Direction.E, new GeometryVector(0.8D, 0.6D, 0.0D),
        Direction.W, new GeometryVector(-0.8D, 0.6D, 0.0D),
        Direction.U, new GeometryVector(0.0D, 0.8D, 0.6D),
        Direction.D, new GeometryVector(0.0D, -0.8D, -0.6D));
    private static final Map<Direction, GeometryVector> SNAP = Map.of(
        Direction.N, new GeometryVector(0.0D, 0.0D, 1.0D),
        Direction.S, new GeometryVector(0.0D, 0.0D, -1.0D),
        Direction.E, new GeometryVector(-1.0D, 0.0D, 0.0D),
        Direction.W, new GeometryVector(1.0D, 0.0D, 0.0D),
        Direction.U, new GeometryVector(0.0D, -1.0D, 0.0D),
        Direction.D, new GeometryVector(0.0D, 1.0D, 0.0D));
    private static final Map<Direction, GeometryVector> MIRROR = Map.of(
        Direction.N, new GeometryVector(0.0D, 0.6D, 0.8D),
        Direction.S, new GeometryVector(0.0D, 0.6D, -0.8D),
        Direction.E, new GeometryVector(-0.8D, 0.6D, 0.0D),
        Direction.W, new GeometryVector(0.8D, 0.6D, 0.0D),
        Direction.U, new GeometryVector(0.0D, -0.8D, 0.6D),
        Direction.D, new GeometryVector(0.0D, 0.8D, -0.6D));

    @Test
    void everyPolicyAgainstEveryExitNormalWithoutGravityFlip() {
        PortalCrossing traversive = entering(Direction.N, ENTRY_LOOK, true);
        for (Direction exit : Direction.values()) {
            PortalFrame outFrame = PortalFrame.canonical(exit);
            assertGeometryVector(FRAME.get(exit), OrientationTransform.direction(traversive, outFrame, OrientationPolicy.FRAME, false), "FRAME " + exit);
            assertGeometryVector(ENTRY_LOOK, OrientationTransform.direction(traversive, outFrame, OrientationPolicy.LOOK, false), "LOOK " + exit);
            assertGeometryVector(SNAP.get(exit), OrientationTransform.direction(traversive, outFrame, OrientationPolicy.SNAP, false), "SNAP " + exit);
            assertGeometryVector(MIRROR.get(exit), OrientationTransform.direction(traversive, outFrame, OrientationPolicy.MIRROR, false), "MIRROR " + exit);
        }
    }

    @Test
    void frameMatchesTodaysOutLookExactly() {
        PortalCrossing traversive = entering(Direction.N, ENTRY_LOOK, true);
        for (Direction exit : Direction.values()) {
            PortalFrame outFrame = PortalFrame.canonical(exit);
            assertGeometryVector(traversive.outLook(outFrame), OrientationTransform.direction(traversive, outFrame, OrientationPolicy.FRAME, false), "FRAME " + exit);
        }
    }

    @Test
    void gravityFlipOnlyTouchesVerticalExits() {
        PortalCrossing traversive = entering(Direction.N, ENTRY_LOOK, true);
        for (Direction exit : new Direction[] {Direction.N, Direction.S, Direction.E, Direction.W}) {
            PortalFrame outFrame = PortalFrame.canonical(exit);
            assertGeometryVector(FRAME.get(exit), OrientationTransform.direction(traversive, outFrame, OrientationPolicy.FRAME, true), "FRAME flip " + exit);
            assertGeometryVector(MIRROR.get(exit), OrientationTransform.direction(traversive, outFrame, OrientationPolicy.MIRROR, true), "MIRROR flip " + exit);
        }
    }

    @Test
    void gravityFlipRestoresAnUprightLookWhenAWallLeadsIntoAFloorOrCeiling() {
        PortalCrossing traversive = entering(Direction.N, ENTRY_LOOK, true);
        assertGeometryVector(new GeometryVector(0.0D, 0.6D, -0.8D), OrientationTransform.direction(traversive, PortalFrame.canonical(Direction.U), OrientationPolicy.FRAME, true), "floor");
        assertGeometryVector(new GeometryVector(0.0D, 0.6D, -0.8D), OrientationTransform.direction(traversive, PortalFrame.canonical(Direction.D), OrientationPolicy.FRAME, true), "ceiling");

        PortalCrossing sideways = entering(Direction.N, new GeometryVector(0.6D, 0.0D, -0.8D), true);
        assertGeometryVector(new GeometryVector(0.6D, 0.0D, -0.8D), OrientationTransform.direction(sideways, PortalFrame.canonical(Direction.U), OrientationPolicy.FRAME, true), "floor sideways");
        assertGeometryVector(new GeometryVector(0.6D, 0.0D, -0.8D), OrientationTransform.direction(sideways, PortalFrame.canonical(Direction.D), OrientationPolicy.FRAME, true), "ceiling sideways");
    }

    @Test
    void gravityFlipTurnsAFallIntoAHorizontalHeadingOutOfACeiling() {
        PortalCrossing falling = entering(Direction.U, new GeometryVector(0.0D, -1.0D, 0.0D), true);
        assertGeometryVector(new GeometryVector(0.0D, 1.0D, 0.0D), OrientationTransform.direction(falling, PortalFrame.canonical(Direction.D), OrientationPolicy.FRAME, false), "no flip");
        assertGeometryVector(new GeometryVector(0.0D, 0.0D, 1.0D), OrientationTransform.direction(falling, PortalFrame.canonical(Direction.D), OrientationPolicy.FRAME, true), "flip");
        assertGeometryVector(new GeometryVector(0.0D, 0.0D, 1.0D), OrientationTransform.direction(falling, PortalFrame.canonical(Direction.N), OrientationPolicy.FRAME, true), "wall exit is untouched");
    }

    @Test
    void gravityFlipLeavesAbsoluteLookAloneAndRotatesSnap() {
        PortalCrossing traversive = entering(Direction.N, ENTRY_LOOK, true);
        assertGeometryVector(ENTRY_LOOK, OrientationTransform.direction(traversive, PortalFrame.canonical(Direction.U), OrientationPolicy.LOOK, true), "LOOK flip");
        assertGeometryVector(new GeometryVector(0.0D, 0.0D, 1.0D), OrientationTransform.direction(traversive, PortalFrame.canonical(Direction.U), OrientationPolicy.SNAP, true), "SNAP flip floor");
    }

    @Test
    void backSideEntryUsesTheFlippedFrames() {
        PortalCrossing traversive = entering(Direction.N, ENTRY_LOOK, false);
        PortalFrame outFrame = PortalFrame.canonical(Direction.E);
        assertGeometryVector(new GeometryVector(0.8D, 0.6D, 0.0D), OrientationTransform.direction(traversive, outFrame, OrientationPolicy.FRAME, false), "FRAME back");
        assertGeometryVector(new GeometryVector(1.0D, 0.0D, 0.0D), OrientationTransform.direction(traversive, outFrame, OrientationPolicy.SNAP, false), "SNAP back");
    }

    @Test
    void lookConvertsDirectionsToBukkitYawAndPitch() {
        assertLook(0.0F, 0.0F, OrientationTransform.Look.of(new GeometryVector(0.0D, 0.0D, 1.0D)));
        assertLook(90.0F, 0.0F, OrientationTransform.Look.of(new GeometryVector(-1.0D, 0.0D, 0.0D)));
        assertLook(180.0F, 0.0F, OrientationTransform.Look.of(new GeometryVector(0.0D, 0.0D, -1.0D)));
        assertLook(270.0F, 0.0F, OrientationTransform.Look.of(new GeometryVector(1.0D, 0.0D, 0.0D)));
        assertLook(0.0F, -90.0F, OrientationTransform.Look.of(new GeometryVector(0.0D, 1.0D, 0.0D)));
        assertLook(0.0F, 90.0F, OrientationTransform.Look.of(new GeometryVector(0.0D, -1.0D, 0.0D)));
        assertLook(0.0F, -36.869896F, OrientationTransform.Look.of(new GeometryVector(0.0D, 0.6D, 0.8D)));

        PortalCrossing traversive = entering(Direction.N, ENTRY_LOOK, true);
        OrientationTransform.Look snapped = OrientationTransform.apply(traversive, PortalFrame.canonical(Direction.N), OrientationPolicy.SNAP, false);
        assertLook(0.0F, 0.0F, snapped);
    }

    private static PortalCrossing entering(Direction normal, GeometryVector look, boolean frontSide) {
        PortalFrame inFrame = PortalFrame.canonical(normal).view(frontSide);
        return new PortalCrossing(inFrame, new GeometryVector(10.0D, 64.0D, 20.0D),
            new GeometryVector(10.5D, 64.5D, 20.0D), new GeometryVector(0.0D, 0.0D, -0.4D), look, frontSide);
    }

    private static void assertLook(float yaw, float pitch, OrientationTransform.Look look) {
        assertEquals(yaw, look.yaw(), 1e-4F, "yaw");
        assertEquals(pitch, look.pitch(), 1e-4F, "pitch");
    }

    private static void assertGeometryVector(GeometryVector expected, GeometryVector actual, String label) {
        assertEquals(expected.getX(), actual.getX(), EPSILON, label + " x");
        assertEquals(expected.getY(), actual.getY(), EPSILON, label + " y");
        assertEquals(expected.getZ(), actual.getZ(), EPSILON, label + " z");
    }
}
