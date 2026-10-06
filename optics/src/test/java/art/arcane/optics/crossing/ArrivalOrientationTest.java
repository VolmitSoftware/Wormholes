package art.arcane.optics.crossing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;

final class ArrivalOrientationTest {
    private static final double EPSILON = 1e-9D;
    private static final Vec3d ENTRY_LOOK = new Vec3d(0.0D, 0.6D, -0.8D);
    private static final Vec3d ENTRY_ORIGIN = new Vec3d(10.0D, 64.0D, 20.0D);
    private static final Vec3d EXIT_ORIGIN = new Vec3d(-40.5D, 90.0D, 300.5D);

    private static final Map<Face, Vec3d> FRAME = Map.of(
        Face.N, new Vec3d(0.0D, 0.6D, -0.8D),
        Face.S, new Vec3d(0.0D, 0.6D, 0.8D),
        Face.E, new Vec3d(0.8D, 0.6D, 0.0D),
        Face.W, new Vec3d(-0.8D, 0.6D, 0.0D),
        Face.U, new Vec3d(0.0D, 0.8D, 0.6D),
        Face.D, new Vec3d(0.0D, -0.8D, -0.6D));
    private static final Map<Face, Vec3d> SNAP = Map.of(
        Face.N, new Vec3d(0.0D, 0.0D, 1.0D),
        Face.S, new Vec3d(0.0D, 0.0D, -1.0D),
        Face.E, new Vec3d(-1.0D, 0.0D, 0.0D),
        Face.W, new Vec3d(1.0D, 0.0D, 0.0D),
        Face.U, new Vec3d(0.0D, -1.0D, 0.0D),
        Face.D, new Vec3d(0.0D, 1.0D, 0.0D));
    private static final Map<Face, Vec3d> MIRROR = Map.of(
        Face.N, new Vec3d(0.0D, 0.6D, 0.8D),
        Face.S, new Vec3d(0.0D, 0.6D, -0.8D),
        Face.E, new Vec3d(-0.8D, 0.6D, 0.0D),
        Face.W, new Vec3d(0.8D, 0.6D, 0.0D),
        Face.U, new Vec3d(0.0D, -0.8D, 0.6D),
        Face.D, new Vec3d(0.0D, 0.8D, -0.6D));

    @Test
    void everyRuleAgainstEveryExitNormalWithoutGravityFlip() {
        PlaneCrossing crossing = entering(Face.N, ENTRY_LOOK, true);
        for (Face exit : Face.values()) {
            Frame exitFrame = Frame.canonical(exit);
            assertVector(FRAME.get(exit), ArrivalOrientation.direction(crossing, exitFrame, OrientationRule.FRAME, false), "FRAME " + exit);
            assertVector(ENTRY_LOOK, ArrivalOrientation.direction(crossing, exitFrame, OrientationRule.LOOK, false), "LOOK " + exit);
            assertVector(SNAP.get(exit), ArrivalOrientation.direction(crossing, exitFrame, OrientationRule.SNAP, false), "SNAP " + exit);
            assertVector(MIRROR.get(exit), ArrivalOrientation.direction(crossing, exitFrame, OrientationRule.MIRROR, false), "MIRROR " + exit);
        }
    }

    @Test
    void frameMatchesTheOutLookExactlyAndNullMeansFrame() {
        PlaneCrossing crossing = entering(Face.N, ENTRY_LOOK, true);
        for (Face exit : Face.values()) {
            Frame exitFrame = Frame.canonical(exit);
            assertVector(crossing.outLook(exitFrame), ArrivalOrientation.direction(crossing, exitFrame, OrientationRule.FRAME, false), "FRAME " + exit);
            assertVector(crossing.outLook(exitFrame), ArrivalOrientation.direction(crossing, exitFrame, null, false), "null " + exit);
        }
    }

    @Test
    void gravityFlipOnlyTouchesVerticalExits() {
        PlaneCrossing crossing = entering(Face.N, ENTRY_LOOK, true);
        for (Face exit : new Face[] {Face.N, Face.S, Face.E, Face.W}) {
            Frame exitFrame = Frame.canonical(exit);
            assertVector(FRAME.get(exit), ArrivalOrientation.direction(crossing, exitFrame, OrientationRule.FRAME, true), "FRAME flip " + exit);
            assertVector(MIRROR.get(exit), ArrivalOrientation.direction(crossing, exitFrame, OrientationRule.MIRROR, true), "MIRROR flip " + exit);
        }
    }

    @Test
    void gravityFlipRestoresAnUprightLookWhenAWallLeadsIntoAFloorOrCeiling() {
        PlaneCrossing crossing = entering(Face.N, ENTRY_LOOK, true);
        assertVector(new Vec3d(0.0D, 0.6D, -0.8D), ArrivalOrientation.direction(crossing, Frame.canonical(Face.U), OrientationRule.FRAME, true), "floor");
        assertVector(new Vec3d(0.0D, 0.6D, -0.8D), ArrivalOrientation.direction(crossing, Frame.canonical(Face.D), OrientationRule.FRAME, true), "ceiling");

        PlaneCrossing sideways = entering(Face.N, new Vec3d(0.6D, 0.0D, -0.8D), true);
        assertVector(new Vec3d(0.6D, 0.0D, -0.8D), ArrivalOrientation.direction(sideways, Frame.canonical(Face.U), OrientationRule.FRAME, true), "floor sideways");
        assertVector(new Vec3d(0.6D, 0.0D, -0.8D), ArrivalOrientation.direction(sideways, Frame.canonical(Face.D), OrientationRule.FRAME, true), "ceiling sideways");
    }

    @Test
    void gravityFlipTurnsAFallIntoAHorizontalHeadingOutOfACeiling() {
        PlaneCrossing falling = entering(Face.U, new Vec3d(0.0D, -1.0D, 0.0D), true);
        assertVector(new Vec3d(0.0D, 1.0D, 0.0D), ArrivalOrientation.direction(falling, Frame.canonical(Face.D), OrientationRule.FRAME, false), "no flip");
        assertVector(new Vec3d(0.0D, 0.0D, 1.0D), ArrivalOrientation.direction(falling, Frame.canonical(Face.D), OrientationRule.FRAME, true), "flip");
        assertVector(new Vec3d(0.0D, 0.0D, 1.0D), ArrivalOrientation.direction(falling, Frame.canonical(Face.N), OrientationRule.FRAME, true), "wall exit is untouched");
    }

    @Test
    void gravityFlipLeavesAbsoluteLookAloneAndRotatesSnap() {
        PlaneCrossing crossing = entering(Face.N, ENTRY_LOOK, true);
        assertVector(ENTRY_LOOK, ArrivalOrientation.direction(crossing, Frame.canonical(Face.U), OrientationRule.LOOK, true), "LOOK flip");
        assertVector(new Vec3d(0.0D, 0.0D, 1.0D), ArrivalOrientation.direction(crossing, Frame.canonical(Face.U), OrientationRule.SNAP, true), "SNAP flip floor");
    }

    @Test
    void backSideEntryUsesTheFlippedFrames() {
        PlaneCrossing crossing = entering(Face.N, ENTRY_LOOK, false);
        Frame exitFrame = Frame.canonical(Face.E);
        assertVector(new Vec3d(0.8D, 0.6D, 0.0D), ArrivalOrientation.direction(crossing, exitFrame, OrientationRule.FRAME, false), "FRAME back");
        assertVector(new Vec3d(1.0D, 0.0D, 0.0D), ArrivalOrientation.direction(crossing, exitFrame, OrientationRule.SNAP, false), "SNAP back");
    }

    @Test
    void lookConvertsDirectionsToYawAndPitch() {
        assertLook(0.0F, 0.0F, Angles.Look.of(new Vec3d(0.0D, 0.0D, 1.0D)));
        assertLook(270.0F, 0.0F, Angles.Look.of(new Vec3d(1.0D, 0.0D, 0.0D)));
        PlaneCrossing crossing = entering(Face.N, ENTRY_LOOK, true);
        assertLook(0.0F, 0.0F, ArrivalOrientation.apply(crossing, Frame.canonical(Face.N), OrientationRule.SNAP, false));
    }

    @Test
    void arrivalAppliesTheRuleToCurrentAndPreviousLooksWithoutAStreak() {
        PlaneCrossing crossing = entering(Face.N, Angles.direction(170.0F, -20.0F), true);
        Frame exitFrame = Frame.canonical(Face.E);
        Pose source = pose(170.0F, -20.0F, 166.0F, -18.0F);
        Pose crossed = PoseTransform.apply(source, CrossingFixtures.toward(crossing, exitFrame, EXIT_ORIGIN));

        Pose framed = PoseTransform.arrive(crossed, crossing, exitFrame, OrientationRule.FRAME, false, null, 0.0D);
        assertAngle(crossed.yaw(), framed.yaw());
        assertAngle(crossed.previousYaw(), framed.previousYaw());
        assertAngle(crossed.pitch(), framed.pitch());
        assertAngle(crossed.previousPitch(), framed.previousPitch());
        assertEquals(crossed.position(), framed.position());

        Pose absolute = PoseTransform.arrive(crossed, crossing, exitFrame, OrientationRule.LOOK, false, null, 0.0D);
        assertAngle(4.0F, absolute.yaw() - absolute.previousYaw());
        assertAngle(170.0F, Angles.unwrap(absolute.yaw(), 170.0F));
        assertAngle(166.0F, Angles.unwrap(absolute.previousYaw(), 166.0F));

        Pose snapped = PoseTransform.arrive(crossed, crossing, exitFrame, OrientationRule.SNAP, false, null, 0.0D);
        assertAngle(snapped.yaw(), snapped.previousYaw());
        assertAngle(0.0F, snapped.pitch());
        assertAngle(0.0F, snapped.previousPitch());
        assertAngle(snapped.yaw(), snapped.bodyYaw());
        assertAngle(snapped.yaw(), snapped.previousHeadYaw());
        assertAngle(90.0F, Angles.unwrap(snapped.yaw(), 90.0F));

        Pose mirrored = PoseTransform.arrive(crossed, crossing, exitFrame, OrientationRule.MIRROR, false, null, 0.0D);
        assertAngle(-(crossed.yaw() - crossed.previousYaw()), mirrored.yaw() - mirrored.previousYaw());
        assertAngle(crossed.previousPitch(), mirrored.previousPitch());
    }

    @Test
    void gravityFlipRotatesThePreviousFieldsWithTheCurrentOnes() {
        PlaneCrossing crossing = entering(Face.N, Angles.direction(180.0F, -36.869896F), true);
        Frame ceiling = Frame.canonical(Face.D);
        Pose source = pose(180.0F, -36.869896F, 176.0F, -30.0F);
        Pose crossed = PoseTransform.apply(source, CrossingFixtures.toward(crossing, ceiling, EXIT_ORIGIN));
        Pose flipped = PoseTransform.arrive(crossed, crossing, ceiling, OrientationRule.FRAME, true, null, 0.0D);

        Angles.Look expectedCurrent = ArrivalOrientation.apply(crossing, ceiling, OrientationRule.FRAME, true);
        PlaneCrossing previousCrossing = new PlaneCrossing(crossing.frame(), crossing.origin(), crossing.point(), crossing.velocity(),
            Angles.direction(176.0F, -30.0F), crossing.frontSide());
        Angles.Look expectedPrevious = ArrivalOrientation.apply(previousCrossing, ceiling, OrientationRule.FRAME, true);
        assertAngle(expectedCurrent.yaw(), Angles.unwrap(flipped.yaw(), expectedCurrent.yaw()));
        assertAngle(expectedCurrent.pitch(), flipped.pitch());
        assertAngle(expectedPrevious.yaw(), Angles.unwrap(flipped.previousYaw(), expectedPrevious.yaw()));
        assertAngle(expectedPrevious.pitch(), flipped.previousPitch());
        assertAngle(-36.869896F, flipped.pitch());
        assertAngle(-30.0F, flipped.previousPitch());
        assertAngle(4.0F, flipped.yaw() - flipped.previousYaw());

        Pose unflipped = PoseTransform.arrive(crossed, crossing, ceiling, OrientationRule.FRAME, false, null, 0.0D);
        assertAngle(crossed.pitch(), unflipped.pitch());
        assertAngle(crossed.previousPitch(), unflipped.previousPitch());
    }

    private static PlaneCrossing entering(Face normal, Vec3d look, boolean frontSide) {
        Frame inFrame = Frame.canonical(normal).view(frontSide);
        return new PlaneCrossing(inFrame, ENTRY_ORIGIN, new Vec3d(10.5D, 64.5D, 20.0D), new Vec3d(0.0D, 0.0D, -0.4D), look, frontSide);
    }

    private static Pose pose(float yaw, float pitch, float previousYaw, float previousPitch) {
        return new Pose(new Vec3d(10.5D, 64.5D, 19.9D), new Vec3d(10.5D, 64.5D, 20.3D), new Vec3d(10.5D, 64.5D, 20.3D),
            new Vec3d(0.0D, 0.0D, -0.4D), yaw, pitch, previousYaw, previousPitch, yaw, previousYaw, yaw, previousYaw);
    }

    private static void assertAngle(float expected, float actual) {
        assertEquals(expected, actual, 1e-3F);
    }

    private static void assertLook(float yaw, float pitch, Angles.Look look) {
        assertEquals(yaw, look.yaw(), 1e-4F, "yaw");
        assertEquals(pitch, look.pitch(), 1e-4F, "pitch");
    }

    private static void assertVector(Vec3d expected, Vec3d actual, String label) {
        assertEquals(expected.x(), actual.x(), EPSILON, label + " x");
        assertEquals(expected.y(), actual.y(), EPSILON, label + " y");
        assertEquals(expected.z(), actual.z(), EPSILON, label + " z");
    }
}
