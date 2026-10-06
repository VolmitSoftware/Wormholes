package art.arcane.wormholes.transit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import art.arcane.optics.math.Vec3;
import org.junit.jupiter.api.Test;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.math.Face;

/**
 * Expected vectors are computed by hand from the canonical triads:
 * N(normal N, up U, right E), S(S, U, W), E(E, U, S), W(W, U, N), U(U, up S, right E), D(D, up N, right E).
 * The entry look (0, 0.6, -0.8) through canonical(N) has components right 0, up 0.6, normal 0.8.
 */
final class OrientationTransformTest {
    private static final double EPSILON = 1e-9D;
    private static final Vec3 ENTRY_LOOK = new Vec3(0.0D, 0.6D, -0.8D);

    private static final Map<Face, Vec3> FRAME = Map.of(
        Face.N, new Vec3(0.0D, 0.6D, -0.8D),
        Face.S, new Vec3(0.0D, 0.6D, 0.8D),
        Face.E, new Vec3(0.8D, 0.6D, 0.0D),
        Face.W, new Vec3(-0.8D, 0.6D, 0.0D),
        Face.U, new Vec3(0.0D, 0.8D, 0.6D),
        Face.D, new Vec3(0.0D, -0.8D, -0.6D));
    private static final Map<Face, Vec3> SNAP = Map.of(
        Face.N, new Vec3(0.0D, 0.0D, 1.0D),
        Face.S, new Vec3(0.0D, 0.0D, -1.0D),
        Face.E, new Vec3(-1.0D, 0.0D, 0.0D),
        Face.W, new Vec3(1.0D, 0.0D, 0.0D),
        Face.U, new Vec3(0.0D, -1.0D, 0.0D),
        Face.D, new Vec3(0.0D, 1.0D, 0.0D));
    private static final Map<Face, Vec3> MIRROR = Map.of(
        Face.N, new Vec3(0.0D, 0.6D, 0.8D),
        Face.S, new Vec3(0.0D, 0.6D, -0.8D),
        Face.E, new Vec3(-0.8D, 0.6D, 0.0D),
        Face.W, new Vec3(0.8D, 0.6D, 0.0D),
        Face.U, new Vec3(0.0D, -0.8D, 0.6D),
        Face.D, new Vec3(0.0D, 0.8D, -0.6D));

    @Test
    void everyPolicyAgainstEveryExitNormalWithoutGravityFlip() {
        PlaneCrossing traversive = entering(Face.N, ENTRY_LOOK, true);
        for (Face exit : Face.values()) {
            Frame outFrame = Frame.canonical(exit);
            assertGeometryVector(FRAME.get(exit), OrientationTransform.direction(traversive, outFrame, OrientationPolicy.FRAME, false), "FRAME " + exit);
            assertGeometryVector(ENTRY_LOOK, OrientationTransform.direction(traversive, outFrame, OrientationPolicy.LOOK, false), "LOOK " + exit);
            assertGeometryVector(SNAP.get(exit), OrientationTransform.direction(traversive, outFrame, OrientationPolicy.SNAP, false), "SNAP " + exit);
            assertGeometryVector(MIRROR.get(exit), OrientationTransform.direction(traversive, outFrame, OrientationPolicy.MIRROR, false), "MIRROR " + exit);
        }
    }

    @Test
    void frameMatchesTodaysOutLookExactly() {
        PlaneCrossing traversive = entering(Face.N, ENTRY_LOOK, true);
        for (Face exit : Face.values()) {
            Frame outFrame = Frame.canonical(exit);
            assertGeometryVector(traversive.outLook(outFrame), OrientationTransform.direction(traversive, outFrame, OrientationPolicy.FRAME, false), "FRAME " + exit);
        }
    }

    @Test
    void gravityFlipOnlyTouchesVerticalExits() {
        PlaneCrossing traversive = entering(Face.N, ENTRY_LOOK, true);
        for (Face exit : new Face[] {Face.N, Face.S, Face.E, Face.W}) {
            Frame outFrame = Frame.canonical(exit);
            assertGeometryVector(FRAME.get(exit), OrientationTransform.direction(traversive, outFrame, OrientationPolicy.FRAME, true), "FRAME flip " + exit);
            assertGeometryVector(MIRROR.get(exit), OrientationTransform.direction(traversive, outFrame, OrientationPolicy.MIRROR, true), "MIRROR flip " + exit);
        }
    }

    @Test
    void gravityFlipRestoresAnUprightLookWhenAWallLeadsIntoAFloorOrCeiling() {
        PlaneCrossing traversive = entering(Face.N, ENTRY_LOOK, true);
        assertGeometryVector(new Vec3(0.0D, 0.6D, -0.8D), OrientationTransform.direction(traversive, Frame.canonical(Face.U), OrientationPolicy.FRAME, true), "floor");
        assertGeometryVector(new Vec3(0.0D, 0.6D, -0.8D), OrientationTransform.direction(traversive, Frame.canonical(Face.D), OrientationPolicy.FRAME, true), "ceiling");

        PlaneCrossing sideways = entering(Face.N, new Vec3(0.6D, 0.0D, -0.8D), true);
        assertGeometryVector(new Vec3(0.6D, 0.0D, -0.8D), OrientationTransform.direction(sideways, Frame.canonical(Face.U), OrientationPolicy.FRAME, true), "floor sideways");
        assertGeometryVector(new Vec3(0.6D, 0.0D, -0.8D), OrientationTransform.direction(sideways, Frame.canonical(Face.D), OrientationPolicy.FRAME, true), "ceiling sideways");
    }

    @Test
    void gravityFlipTurnsAFallIntoAHorizontalHeadingOutOfACeiling() {
        PlaneCrossing falling = entering(Face.U, new Vec3(0.0D, -1.0D, 0.0D), true);
        assertGeometryVector(new Vec3(0.0D, 1.0D, 0.0D), OrientationTransform.direction(falling, Frame.canonical(Face.D), OrientationPolicy.FRAME, false), "no flip");
        assertGeometryVector(new Vec3(0.0D, 0.0D, 1.0D), OrientationTransform.direction(falling, Frame.canonical(Face.D), OrientationPolicy.FRAME, true), "flip");
        assertGeometryVector(new Vec3(0.0D, 0.0D, 1.0D), OrientationTransform.direction(falling, Frame.canonical(Face.N), OrientationPolicy.FRAME, true), "wall exit is untouched");
    }

    @Test
    void gravityFlipLeavesAbsoluteLookAloneAndRotatesSnap() {
        PlaneCrossing traversive = entering(Face.N, ENTRY_LOOK, true);
        assertGeometryVector(ENTRY_LOOK, OrientationTransform.direction(traversive, Frame.canonical(Face.U), OrientationPolicy.LOOK, true), "LOOK flip");
        assertGeometryVector(new Vec3(0.0D, 0.0D, 1.0D), OrientationTransform.direction(traversive, Frame.canonical(Face.U), OrientationPolicy.SNAP, true), "SNAP flip floor");
    }

    @Test
    void backSideEntryUsesTheFlippedFrames() {
        PlaneCrossing traversive = entering(Face.N, ENTRY_LOOK, false);
        Frame outFrame = Frame.canonical(Face.E);
        assertGeometryVector(new Vec3(0.8D, 0.6D, 0.0D), OrientationTransform.direction(traversive, outFrame, OrientationPolicy.FRAME, false), "FRAME back");
        assertGeometryVector(new Vec3(1.0D, 0.0D, 0.0D), OrientationTransform.direction(traversive, outFrame, OrientationPolicy.SNAP, false), "SNAP back");
    }

    @Test
    void lookConvertsDirectionsToBukkitYawAndPitch() {
        assertLook(0.0F, 0.0F, OrientationTransform.Look.of(new Vec3(0.0D, 0.0D, 1.0D)));
        assertLook(90.0F, 0.0F, OrientationTransform.Look.of(new Vec3(-1.0D, 0.0D, 0.0D)));
        assertLook(180.0F, 0.0F, OrientationTransform.Look.of(new Vec3(0.0D, 0.0D, -1.0D)));
        assertLook(270.0F, 0.0F, OrientationTransform.Look.of(new Vec3(1.0D, 0.0D, 0.0D)));
        assertLook(0.0F, -90.0F, OrientationTransform.Look.of(new Vec3(0.0D, 1.0D, 0.0D)));
        assertLook(0.0F, 90.0F, OrientationTransform.Look.of(new Vec3(0.0D, -1.0D, 0.0D)));
        assertLook(0.0F, -36.869896F, OrientationTransform.Look.of(new Vec3(0.0D, 0.6D, 0.8D)));

        PlaneCrossing traversive = entering(Face.N, ENTRY_LOOK, true);
        OrientationTransform.Look snapped = OrientationTransform.apply(traversive, Frame.canonical(Face.N), OrientationPolicy.SNAP, false);
        assertLook(0.0F, 0.0F, snapped);
    }

    private static PlaneCrossing entering(Face normal, Vec3 look, boolean frontSide) {
        Frame inFrame = Frame.canonical(normal).view(frontSide);
        return new PlaneCrossing(inFrame, new Vec3(10.0D, 64.0D, 20.0D),
            new Vec3(10.5D, 64.5D, 20.0D), new Vec3(0.0D, 0.0D, -0.4D), look, frontSide);
    }

    private static void assertLook(float yaw, float pitch, OrientationTransform.Look look) {
        assertEquals(yaw, look.yaw(), 1e-4F, "yaw");
        assertEquals(pitch, look.pitch(), 1e-4F, "pitch");
    }

    private static void assertGeometryVector(Vec3 expected, Vec3 actual, String label) {
        assertEquals(expected.getX(), actual.getX(), EPSILON, label + " x");
        assertEquals(expected.getY(), actual.getY(), EPSILON, label + " y");
        assertEquals(expected.getZ(), actual.getZ(), EPSILON, label + " z");
    }
}
