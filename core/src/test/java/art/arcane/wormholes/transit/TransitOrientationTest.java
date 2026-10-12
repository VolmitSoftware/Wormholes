package art.arcane.wormholes.transit;

import art.arcane.optics.crossing.LookTransfer;
import art.arcane.optics.crossing.MomentumRule;
import art.arcane.optics.crossing.OrientationRule;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.crossing.Pose;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TransitOrientationTest {
    private static final Vec3d ORIGIN = new Vec3d(0.0D, 64.0D, 0.0D);
    private static final MomentumRule PRESERVE = new MomentumRule(MomentumRule.Mode.PRESERVE, 1.0D, 0.0D, null);

    @Test
    public void repeatedHighSpeedHorizontalCrossingsKeepDownwardAimAndMomentumAtEveryPole() {
        Frame floor = Frame.canonical(Face.U);
        Similarity toward = Similarity.between(floor, ORIGIN, floor, ORIGIN.add(new Vec3d(0.0D, 40.0D, 0.0D)), 1.0D);
        for (float yaw : new float[] {-170.0F, -90.0F, 0.0F, 70.0F, 180.0F, 359.0F}) {
            for (float pitch : new float[] {-90.0F, -89.99F, 0.0F, 89.99F, 90.0F}) {
                Pose source = pose(yaw, pitch, yaw, pitch);
                for (int count = 0; count < 64; count++) {
                    Pose arrived = arrive(source, floor, floor, toward, OrientationRule.FRAME, true);
                    assertLook(Angles.direction(yaw, pitch), arrived.yaw(), arrived.pitch());
                    assertEquals(source.velocity(), arrived.velocity());
                    assertEquals(source.yaw(), arrived.yaw(), 1.0E-4F);
                    assertEquals(0.0F, TransitOrientation.arrivalRoll(source, crossing(source, floor), floor, OrientationRule.FRAME, true), 1.0E-3F);
                    source = arrived;
                }
            }
        }
    }

    @Test
    public void previousLookNearAPoleKeepsItsActualDirectionInsteadOfClampingToThePole() {
        Frame floor = Frame.canonical(Face.U);
        Frame ceiling = Frame.canonical(Face.D);
        Similarity toward = Similarity.between(floor, ORIGIN, ceiling, ORIGIN, 1.0D);
        for (float[] pitches : new float[][] {{90.0F, 70.0F}, {70.0F, 90.0F}, {90.0F, 89.99F}, {89.99F, 90.0F}}) {
            Pose source = pose(30.0F, pitches[0], 30.0F, pitches[1]);
            Pose arrived = arrive(source, floor, ceiling, toward, OrientationRule.FRAME, true);
            assertLook(toward.rigid().vector(Angles.direction(source.yaw(), source.pitch())), arrived.yaw(), arrived.pitch());
            assertLook(toward.rigid().vector(Angles.direction(source.previousYaw(), source.previousPitch())), arrived.previousYaw(), arrived.previousPitch());
            assertTrue(Math.abs(arrived.yaw() - arrived.previousYaw()) < 1.0E-3F);
            float roll = TransitOrientation.arrivalRoll(source, crossing(source, floor), ceiling, OrientationRule.FRAME, true);
            assertVector(toward.rigid().vector(LookTransfer.cameraUp(source.yaw(), source.pitch())), up(arrived.yaw(), arrived.pitch(), roll));
        }
    }

    @Test
    public void aDeliberateQuarterTurnAndItsInversePreserveOrdinaryAndPoleLooks() {
        Frame floor = Frame.canonical(Face.U);
        Frame twisted = floor.rotateClockwise();
        Similarity toward = Similarity.between(floor, ORIGIN, twisted, ORIGIN, 1.0D);
        for (float pitch : new float[] {-90.0F, -89.0F, 0.0F, 40.0F, 89.0F, 90.0F}) {
            Pose source = pose(35.0F, pitch, 35.0F, pitch);
            Pose turned = arrive(source, floor, twisted, toward, OrientationRule.FRAME, true);
            assertLook(toward.rigid().vector(Angles.direction(source.yaw(), source.pitch())), turned.yaw(), turned.pitch());
            assertEquals(90.0F, Math.abs(Angles.unwrap(turned.yaw() - source.yaw(), 0.0F)), 1.0E-3F);
            Pose restored = arrive(turned, twisted, floor, toward.inverse(), OrientationRule.FRAME, true);
            assertLook(Angles.direction(source.yaw(), source.pitch()), restored.yaw(), restored.pitch());
            assertEquals(source.yaw(), Angles.unwrap(restored.yaw(), source.yaw()), 1.0E-3F);
        }
    }

    @Test
    public void lookPolicyPreservesWorldAimWhileWallToFloorGravityCorrectionStillApplies() {
        Frame wall = Frame.canonical(Face.N);
        Frame floor = Frame.canonical(Face.U);
        Similarity toward = Similarity.between(wall, ORIGIN, floor, ORIGIN, 1.0D);
        Pose source = pose(17.0F, 24.0F, 15.0F, 20.0F);
        Pose kept = arrive(source, wall, floor, toward, OrientationRule.LOOK, true);
        assertLook(Angles.direction(source.yaw(), source.pitch()), kept.yaw(), kept.pitch());
        Pose corrected = arrive(source, wall, floor, toward, OrientationRule.FRAME, true);
        Pose uncorrected = arrive(source, wall, floor, toward, OrientationRule.FRAME, false);
        assertTrue(Angles.direction(corrected.yaw(), corrected.pitch()).subtract(Angles.direction(uncorrected.yaw(), uncorrected.pitch())).lengthSquared() > 0.1D);
    }

    @Test
    public void mouseYawChangesAtThePoleRemainInCurrentAndPreviousAim() {
        Frame floor = Frame.canonical(Face.U);
        for (float previousPitch : new float[] {70.0F, 89.0F, 90.0F}) {
            Pose source = pose(35.0F, 90.0F, 20.0F, previousPitch);
            Pose arrived = arrive(source, floor, floor, Similarity.IDENTITY, OrientationRule.FRAME, true);
            assertEquals(35.0F, arrived.yaw(), 1.0E-4F);
            assertEquals(20.0F, arrived.previousYaw(), 1.0E-4F);
            assertEquals(previousPitch, arrived.previousPitch(), 1.0E-4F);
        }
    }

    private static Pose arrive(Pose source, Frame entry, Frame exit, Similarity toward, OrientationRule rule, boolean gravityFlip) {
        return TransitOrientation.arrive(source, crossing(source, entry), toward, exit, rule, gravityFlip, PRESERVE, 0.0D);
    }

    private static PlaneCrossing crossing(Pose source, Frame frame) {
        return new PlaneCrossing(frame, ORIGIN, ORIGIN, source.velocity(), Angles.direction(source.yaw(), source.pitch()), true);
    }

    private static Pose pose(float yaw, float pitch, float previousYaw, float previousPitch) {
        return new Pose(ORIGIN, ORIGIN.add(new Vec3d(0.0D, 12.0D, 0.0D)), ORIGIN.add(new Vec3d(0.0D, 12.0D, 0.0D)),
            new Vec3d(0.0D, -12.0D, 0.0D), yaw, pitch, previousYaw, previousPitch, yaw, previousYaw, yaw, previousYaw);
    }

    private static Vec3d up(float yaw, float pitch, float roll) {
        Vec3d forward = Angles.direction(yaw, pitch);
        Vec3d free = LookTransfer.cameraUp(yaw, pitch);
        double angle = Math.toRadians(roll);
        return free.multiply(Math.cos(angle)).add(forward.cross(free).multiply(Math.sin(angle)));
    }

    private static void assertLook(Vec3d expected, float yaw, float pitch) {
        assertVector(expected, Angles.direction(yaw, pitch));
    }

    private static void assertVector(Vec3d expected, Vec3d actual) {
        assertTrue(expected.subtract(actual).lengthSquared() < 1.0E-10D, "expected " + expected + " but was " + actual);
    }
}
