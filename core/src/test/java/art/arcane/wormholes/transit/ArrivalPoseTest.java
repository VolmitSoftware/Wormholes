package art.arcane.wormholes.transit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import art.arcane.optics.crossing.ArrivalOrientation;
import art.arcane.optics.crossing.LookTransfer;
import art.arcane.optics.crossing.MomentumRule;
import art.arcane.optics.crossing.OrientationRule;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.crossing.Pose;
import art.arcane.optics.crossing.ScaleRule;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.network.client.TravelMessage;

final class ArrivalPoseTest {
    private static final Vec3d ENTRY_ORIGIN = new Vec3d(10.5D, 64.0D, 20.5D);
    private static final Vec3d EXIT_ORIGIN = new Vec3d(-40.5D, 90.0D, 300.5D);
    private static final Frame FLOOR = Frame.canonical(Face.U);
    private static final Frame UPWARD_EXIT = Frame.canonical(Face.D);
    private static final MomentumRule PRESERVE = new MomentumRule(MomentumRule.Mode.PRESERVE, 1.0D, 0.0D, null);

    @Test
    void lookingStraightDownIntoAnUpwardExitArrivesLookingStraightUpWithTheBodyUnderTheHead() {
        Pose source = pose(30.0F, 90.0F, 30.0F, 90.0F, 10.0F, 30.0F);
        Pose arrived = arrive(source, FLOOR, UPWARD_EXIT, OrientationRule.FRAME, false);
        LookTransfer predicted = transfer(FLOOR, UPWARD_EXIT, OrientationRule.FRAME, false, 30.0F, 90.0F);

        assertEquals(-90.0F, arrived.pitch(), 0.0F);
        assertEquals(-90.0F, arrived.previousPitch(), 0.0F);
        assertEquals(predicted.yaw(), Angles.unwrap(arrived.yaw(), predicted.yaw()), 1.0E-3F);
        assertEquals(arrived.yaw(), arrived.previousYaw(), 1.0E-3F);
        assertEquals(arrived.yaw(), arrived.headYaw(), 1.0E-3F);
        assertEquals(20.0F, Math.abs(wrapped(arrived.bodyYaw() - arrived.headYaw())), 1.0E-3F);
        assertEquals(0.0F, ArrivalPose.roll(source, crossing(FLOOR, 30.0F, 90.0F), UPWARD_EXIT, OrientationRule.FRAME, false), 1.0E-3F);
    }

    @Test
    void previousAndCurrentFieldsNeverStraddleThePoleBranch() {
        for (float yaw : new float[] {0.0F, 30.0F, -135.0F, 179.0F}) {
            Pose rising = pose(yaw, 90.0F, yaw, 89.0F, yaw, yaw);
            Pose settling = pose(yaw, 89.0F, yaw, 90.0F, yaw, yaw);
            for (Pose source : new Pose[] {rising, settling}) {
                Pose arrived = arrive(source, FLOOR, UPWARD_EXIT, OrientationRule.FRAME, false);
                assertTrue(Math.abs(arrived.yaw() - arrived.previousYaw()) <= 1.0F, "yaw " + yaw + " " + arrived);
                assertTrue(Math.abs(arrived.headYaw() - arrived.previousHeadYaw()) <= 1.0F, "head " + yaw + " " + arrived);
                assertTrue(Math.abs(arrived.bodyYaw() - arrived.previousBodyYaw()) <= 1.0F, "body " + yaw + " " + arrived);
                assertTrue(Math.abs(wrapped(arrived.bodyYaw() - arrived.headYaw())) <= 1.0F, "body follows head " + yaw + " " + arrived);
                assertTrue(visibleChange(arrived) <= visibleChange(source) + 1.0E-3D, "visible " + yaw + " " + arrived);
            }
        }
    }

    @Test
    void offPoleUpsideDownCarriesKeepAConsistentHalfTurnRoll() {
        Pose source = pose(45.0F, 80.0F, 44.0F, 79.0F, 45.0F, 45.0F);
        Pose arrived = arrive(source, FLOOR, UPWARD_EXIT, OrientationRule.FRAME, false);
        float roll = ArrivalPose.roll(source, crossing(FLOOR, 45.0F, 80.0F), UPWARD_EXIT, OrientationRule.FRAME, false);

        assertEquals(-80.0F, arrived.pitch(), 1.0E-3F);
        assertEquals(180.0F, Math.abs(roll), 1.0E-3F);
        assertTrue(Math.abs(arrived.yaw() - arrived.previousYaw()) <= 1.5F);
        assertTrue(Math.abs(wrapped(arrived.bodyYaw() - arrived.headYaw())) <= 1.0E-3F);
    }

    @Test
    void lookingStraightDownIntoAWallExitArrivesLevelAlongTheExitFacingWithTheHead() {
        for (Face wall : new Face[] {Face.N, Face.S, Face.E, Face.W}) {
            Frame exit = Frame.canonical(wall);
            Pose arrived = arrive(pose(0.0F, 90.0F, 0.0F, 90.0F, 70.0F, 0.0F), FLOOR, exit, OrientationRule.FRAME, false);
            Vec3d exitDirection = exit.getNormal().toVector().multiply(-1.0D);

            assertEquals(0.0F, arrived.pitch(), 1.0E-3F, wall.name());
            assertVector(exitDirection, Angles.direction(arrived.yaw(), arrived.pitch()), 1.0E-5D, wall.name());
            assertEquals(arrived.yaw(), arrived.bodyYaw(), 1.0E-3F, wall.name());
            assertEquals(arrived.yaw(), arrived.previousYaw(), 1.0E-3F, wall.name());
        }
    }

    @Test
    void uprightPairsKeepTheBodyAndHeadOffsets() {
        Frame north = Frame.canonical(Face.N);
        Frame east = Frame.canonical(Face.E);
        Pose source = pose(10.0F, 20.0F, 8.0F, 19.0F, -30.0F, 25.0F);
        Pose arrived = arrive(source, north, east, OrientationRule.FRAME, false);

        assertEquals(20.0F, arrived.pitch(), 1.0E-3F);
        assertEquals(-40.0F, wrapped(arrived.bodyYaw() - arrived.yaw()), 1.0E-3F);
        assertEquals(15.0F, wrapped(arrived.headYaw() - arrived.yaw()), 1.0E-3F);
        assertEquals(-2.0F, arrived.previousYaw() - arrived.yaw(), 1.0E-3F);
    }

    @Test
    void nonPoleLooksMatchTheArrivalTransferForEveryRule() {
        for (Frame entry : frames()) {
            for (Frame exit : frames()) {
                for (OrientationRule rule : OrientationRule.values()) {
                    for (boolean flip : new boolean[] {false, true}) {
                        for (float[] look : new float[][] {{30.0F, 20.0F}, {-120.0F, -35.0F}, {200.0F, 55.0F}}) {
                            LookTransfer expected = transfer(entry, exit, rule, flip, look[0], look[1]);
                            if (Math.abs(expected.pitch()) > 60.0F) {
                                continue;
                            }
                            Pose arrived = arrive(pose(look[0], look[1], look[0], look[1], look[0], look[0]), entry, exit, rule, flip);
                            String context = entry + "->" + exit + " " + rule + " " + flip + " " + look[0] + "/" + look[1];
                            assertEquals(expected.pitch(), arrived.pitch(), 1.0E-4F, context);
                            assertEquals(expected.yaw(), Angles.unwrap(arrived.yaw(), expected.yaw()), 1.0E-3F, context);
                        }
                    }
                }
            }
        }
    }

    @Test
    void nearPoleTurnsStayContinuousThroughEveryFramePair() {
        float[][] steps = {{0.0F, 90.0F, 0.0F, 89.0F}, {0.0F, 89.0F, 0.0F, 90.0F}, {35.0F, 89.5F, 34.0F, 89.0F},
            {-60.0F, -90.0F, -60.0F, -89.2F}, {120.0F, -89.0F, 120.5F, -90.0F}, {10.0F, 0.5F, 10.0F, -0.5F}, {10.0F, -0.5F, 10.0F, 0.5F}};
        for (Frame entry : frames()) {
            for (Frame exit : frames()) {
                for (boolean flip : new boolean[] {false, true}) {
                    for (float[] step : steps) {
                        Pose source = pose(step[0], step[1], step[2], step[3], step[0], step[0]);
                        Pose arrived = arrive(source, entry, exit, OrientationRule.FRAME, flip);
                        String context = entry + "->" + exit + " " + flip + " " + step[0] + "/" + step[1] + " from " + step[2] + "/" + step[3];
                        if (Math.abs(arrived.pitch()) < 60.0F) {
                            continue;
                        }
                        assertTrue(visibleChange(arrived) < 90.0D, context + " " + arrived);
                    }
                }
            }
        }
    }

    @Test
    void scaledArrivalsMapPositionsAndVelocityButNotLooks() {
        Frame north = Frame.canonical(Face.N);
        Frame south = Frame.canonical(Face.S);
        PlaneCrossing crossing = crossing(north, 10.0F, 5.0F);
        Pose source = new Pose(new Vec3d(11.5D, 65.0D, 20.4D), new Vec3d(11.5D, 65.0D, 20.6D), new Vec3d(11.5D, 65.0D, 20.8D),
            new Vec3d(0.0D, 0.0D, -0.4D), 10.0F, 5.0F, 10.0F, 5.0F, 10.0F, 10.0F, 10.0F, 10.0F);
        TravelMessage.ArrivalRules rules = new TravelMessage.ArrivalRules(OrientationRule.FRAME, false, PRESERVE, ScaleRule.ratio(0.25D, 4.0D));
        Similarity rigid = crossing.toward(south, EXIT_ORIGIN, 1.0D);
        Similarity scaled = crossing.toward(south, EXIT_ORIGIN, 3.0D);
        Pose plain = ArrivalPose.arrive(source, crossing, rigid, south, rules);
        Pose grown = ArrivalPose.arrive(source, crossing, scaled, south, rules);

        assertVector(EXIT_ORIGIN.add(plain.position().subtract(EXIT_ORIGIN).multiply(3.0D)), grown.position(), 1.0E-9D, "position");
        assertVector(plain.velocity().multiply(3.0D), grown.velocity(), 1.0E-9D, "velocity");
        assertEquals(plain.yaw(), grown.yaw(), 0.0F);
        assertEquals(plain.pitch(), grown.pitch(), 0.0F);
    }

    @Test
    void momentumClampsAfterTheScale() {
        Frame north = Frame.canonical(Face.N);
        PlaneCrossing crossing = crossing(north, 0.0F, 0.0F);
        Pose source = new Pose(ENTRY_ORIGIN, ENTRY_ORIGIN, ENTRY_ORIGIN, new Vec3d(0.0D, 0.0D, -1.0D), 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
        TravelMessage.ArrivalRules rules = new TravelMessage.ArrivalRules(OrientationRule.FRAME, false,
            new MomentumRule(MomentumRule.Mode.CLAMP, 1.0D, 2.0D, null), ScaleRule.motion());

        Pose arrived = ArrivalPose.arrive(source, crossing, crossing.toward(north, EXIT_ORIGIN, 3.0D), north, rules);

        assertEquals(2.0D, arrived.velocity().length(), 1.0E-9D);
    }

    @Test
    void rigidCarriesKeepTheBranchToo() {
        Similarity toward = Similarity.of(crossing(FLOOR, 0.0F, 90.0F).toward(UPWARD_EXIT, EXIT_ORIGIN), 1.0D);
        Pose carried = ArrivalPose.carry(pose(30.0F, 90.0F, 30.0F, 89.0F, 30.0F, 30.0F), toward);

        assertEquals(-90.0F, carried.pitch(), 0.0F);
        assertTrue(Math.abs(carried.yaw() - carried.previousYaw()) <= 1.0F, carried.toString());
        assertTrue(Math.abs(wrapped(carried.bodyYaw() - carried.yaw())) <= 1.0F, carried.toString());
    }

    private static Pose arrive(Pose source, Frame entry, Frame exit, OrientationRule rule, boolean flip) {
        PlaneCrossing crossing = crossing(entry, source.yaw(), source.pitch());
        TravelMessage.ArrivalRules rules = new TravelMessage.ArrivalRules(rule, flip, PRESERVE, ScaleRule.OFF);
        return ArrivalPose.arrive(source, crossing, crossing.toward(exit, EXIT_ORIGIN, 1.0D), exit, rules);
    }

    private static LookTransfer transfer(Frame entry, Frame exit, OrientationRule rule, boolean flip, float yaw, float pitch) {
        return ArrivalOrientation.transfer(crossing(entry, yaw, pitch), LookTransfer.cameraUp(yaw, pitch), exit, rule, flip);
    }

    private static PlaneCrossing crossing(Frame frame, float yaw, float pitch) {
        return new PlaneCrossing(frame, ENTRY_ORIGIN, ENTRY_ORIGIN, new Vec3d(0.0D, -0.4D, 0.0D), Angles.direction(yaw, pitch), true);
    }

    private static Pose pose(float yaw, float pitch, float previousYaw, float previousPitch, float bodyYaw, float headYaw) {
        return new Pose(ENTRY_ORIGIN, ENTRY_ORIGIN, ENTRY_ORIGIN, new Vec3d(0.0D, -0.4D, 0.0D), yaw, pitch, previousYaw, previousPitch,
            bodyYaw, bodyYaw, headYaw, headYaw + previousYaw - yaw);
    }

    private static double visibleChange(Pose pose) {
        double forward = angle(Angles.direction(pose.previousYaw(), pose.previousPitch()), Angles.direction(pose.yaw(), pose.pitch()));
        double up = angle(LookTransfer.cameraUp(pose.previousYaw(), pose.previousPitch()), LookTransfer.cameraUp(pose.yaw(), pose.pitch()));
        return Math.max(forward, up);
    }

    private static double angle(Vec3d first, Vec3d second) {
        double dot = first.normalize().dot(second.normalize());
        return Math.toDegrees(Math.acos(Math.max(-1.0D, Math.min(1.0D, dot))));
    }

    private static float wrapped(float degrees) {
        return Angles.unwrap(degrees, 0.0F);
    }

    private static List<Frame> frames() {
        List<Frame> frames = new ArrayList<>(24);
        for (Face normal : Face.values()) {
            for (Face up : Face.values()) {
                if (normal.getAxis() != up.getAxis()) {
                    frames.add(Frame.fromNormalUp(normal, up));
                }
            }
        }
        return frames;
    }

    private static void assertVector(Vec3d expected, Vec3d actual, double tolerance, String context) {
        assertEquals(expected.x(), actual.x(), tolerance, context + " x");
        assertEquals(expected.y(), actual.y(), tolerance, context + " y");
        assertEquals(expected.z(), actual.z(), tolerance, context + " z");
    }
}
