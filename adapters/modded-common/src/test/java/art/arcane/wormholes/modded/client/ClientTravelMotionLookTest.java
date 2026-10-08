package art.arcane.wormholes.modded.client;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.crossing.ArrivalOrientation;
import art.arcane.optics.crossing.LookTransfer;
import art.arcane.optics.crossing.MomentumRule;
import art.arcane.optics.crossing.OrientationRule;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.crossing.Pose;
import art.arcane.optics.crossing.ScaleRule;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.render.PortalEnvironmentTest;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.portal.ApertureKind;
import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ClientTravelMotionLookTest extends MinecraftTestBase {
    private static final Vec3d FEET = new Vec3d(1.5D, 64.0D, 1.5D);
    private static final MomentumRule PRESERVE = new MomentumRule(MomentumRule.Mode.PRESERVE, 1.0D, 0.0D, null);

    @Test
    public void lookingStraightDownIntoAFloorThatExitsUpwardArrivesLookingStraightUp() {
        TravelMessage.TravelBegin begin = begin(Face.D, 1.0D);
        Pose source = pose(30.0F, 90.0F, 30.0F, 90.0F);

        Pose arrived = ClientTravelMotion.arrive(begin, source, FEET);
        LookTransfer predicted = ArrivalOrientation.transfer(crossing(begin, 30.0F, 90.0F), LookTransfer.cameraUp(30.0F, 90.0F),
            Frame.canonical(Face.D), OrientationRule.FRAME, false);

        assertEquals(-90.0F, arrived.pitch(), 0.0F);
        assertEquals(-90.0F, arrived.previousPitch(), 0.0F);
        assertEquals(predicted.yaw(), Angles.unwrap(arrived.yaw(), predicted.yaw()), 1.0E-3F);
        assertEquals(arrived.yaw(), arrived.previousYaw(), 1.0E-3F);
        assertEquals(0.0F, ClientTravelMotion.roll(begin, source, FEET), 1.0E-3F);
    }

    @Test
    public void previousTickFieldsStayOnTheCurrentBranchAtThePole() {
        TravelMessage.TravelBegin begin = begin(Face.D, 1.0D);
        for (float[] step : new float[][] {{30.0F, 90.0F, 30.0F, 89.0F}, {30.0F, 89.0F, 30.0F, 90.0F}, {-150.0F, 89.5F, -149.0F, 90.0F}}) {
            Pose arrived = ClientTravelMotion.arrive(begin, pose(step[0], step[1], step[2], step[3]), FEET);
            String context = step[0] + "/" + step[1] + " from " + step[2] + "/" + step[3] + " -> " + arrived;
            assertTrue(context, Math.abs(arrived.yaw() - arrived.previousYaw()) <= 1.5F);
            assertTrue(context, Math.abs(arrived.headYaw() - arrived.previousHeadYaw()) <= 1.5F);
            assertTrue(context, Math.abs(Angles.unwrap(arrived.bodyYaw() - arrived.yaw(), 0.0F)) <= 1.5F);
        }
    }

    @Test
    public void lookingStraightDownIntoAWallExitArrivesLevelAlongTheExit() {
        TravelMessage.TravelBegin begin = begin(Face.N, 1.0D);

        Pose arrived = ClientTravelMotion.arrive(begin, pose(0.0F, 90.0F, 0.0F, 90.0F), FEET);
        Vec3d look = Angles.direction(arrived.yaw(), arrived.pitch());

        assertEquals(0.0F, arrived.pitch(), 1.0E-3F);
        assertEquals(1.0D, look.z(), 1.0E-5D);
    }

    @Test
    public void quarterTwistedPairsReportTheRollLeftForTheEase() {
        TravelMessage.TravelBegin begin = begin(Face.N, 1.0D);

        float roll = ClientTravelMotion.roll(begin, pose(90.0F, 0.0F, 90.0F, 0.0F), FEET);

        assertEquals(90.0F, Math.abs(roll), 1.0E-3F);
    }

    @Test
    public void scaledBeginsMapTheOffsetButNotTheLook() {
        TravelMessage.TravelBegin rigid = begin(Face.D, 1.0D);
        TravelMessage.TravelBegin scaled = begin(Face.D, 3.0D);
        Pose source = pose(30.0F, 60.0F, 30.0F, 60.0F);

        Pose plain = ClientTravelMotion.arrive(rigid, source, FEET);
        Pose grown = ClientTravelMotion.arrive(scaled, source, FEET);
        Vec3d center = scaled.sourceToDestination().point(new Vec3d(1.0D, 64.5D, 1.0D));
        Vec3d rigidCenter = rigid.sourceToDestination().point(new Vec3d(1.0D, 64.5D, 1.0D));

        assertEquals(plain.yaw(), grown.yaw(), 0.0F);
        assertEquals(plain.pitch(), grown.pitch(), 0.0F);
        assertEquals(3.0D * plain.position().subtract(rigidCenter).length(), grown.position().subtract(center).length(), 1.0E-9D);
        assertEquals(3.0D * plain.velocity().length(), grown.velocity().length(), 1.0E-9D);
    }

    private static TravelMessage.TravelBegin begin(Face exit, double scale) {
        ApertureDescriptor geometry = floor();
        Frame source = geometry.frame().view(true);
        Similarity toward = Similarity.between(source, new Vec3d(1.0D, 64.5D, 1.0D), Frame.canonical(exit).view(true), new Vec3d(100.5D, 80.5D, 100.5D), scale);
        return new TravelMessage.TravelBegin(new UUID(1, 2), 3, new UUID(4, 5), "minecraft:overworld", geometry,
            TravelMessage.TravelBegin.destinationToSource(toward), (float) scale,
            new TravelMessage.TravelWorld("minecraft:overworld", "minecraft:overworld", 7, false, false, 63, -64, 384),
            new TravelMessage.TravelPose(100, 80, 100, 0, 0), List.of(new TravelMessage.TravelCoordinate(6, 6)),
            PortalEnvironmentTest.environment(OpticTransform.IDENTITY), 30_000,
            new TravelMessage.ArrivalRules(OrientationRule.FRAME, false, PRESERVE, scale == 1.0D ? ScaleRule.OFF : ScaleRule.motion()), true, 1, true);
    }

    private static ApertureDescriptor floor() {
        return new ApertureDescriptor(0, 64, 0, Face.U.ordinal(), true, 0, false, 2, 2, new long[] {15L}, ShapeDescriptor.FULL,
            0, 0, 1, 64, 0, 0, 0, 0, 0, 0, ApertureKind.FRAME, 0.0D, 0, 11, List.of());
    }

    private static PlaneCrossing crossing(TravelMessage.TravelBegin begin, float yaw, float pitch) {
        return new PlaneCrossing(begin.sourceGeometry().frame().view(true), FEET, FEET, new Vec3d(0.0D, -0.4D, 0.0D), Angles.direction(yaw, pitch), true);
    }

    private static Pose pose(float yaw, float pitch, float previousYaw, float previousPitch) {
        return new Pose(FEET, FEET, FEET, new Vec3d(0.0D, -0.4D, 0.0D), yaw, pitch, previousYaw, previousPitch, yaw, previousYaw, yaw, previousYaw);
    }
}
