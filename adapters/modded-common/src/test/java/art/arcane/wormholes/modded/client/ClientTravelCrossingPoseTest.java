package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.render.PortalEnvironmentTest;
import art.arcane.optics.crossing.ArrivalOrientation;
import art.arcane.optics.crossing.MomentumRule;
import art.arcane.optics.crossing.OrientationRule;
import art.arcane.optics.crossing.ScaleRule;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.crossing.Pose;
import art.arcane.optics.crossing.PoseTransform;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.frame.AxisPermutation;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import art.arcane.wormholes.network.client.TravelMessage;

public class ClientTravelCrossingPoseTest extends MinecraftTestBase {
    private static final MomentumRule PRESERVE = new MomentumRule(MomentumRule.Mode.PRESERVE, 1.0D, 0.0D, null);
    private static final Pose SOURCE = new Pose(new Vec3d(0.5, 1, 0.2), new Vec3d(0.5, 1, 0.5), new Vec3d(0.5, 1, 0.5),
        new Vec3d(0.05, 0.02, 0.3), 5, 10, 2, 8, 4, 1, 6, 3);
    private static final Vec3d CROSSING = new Vec3d(0.5, 1, 0);

    @Test
    public void standingReverseCrossingPreservesExactFeetAboveDestinationFloor() throws ReflectiveOperationException {
        TravelMessage.TravelPose pose = pose(new Vec3(1001.5, 200, 0.4), 0.75f);
        OpticTransform transform = OpticTransform.of(AxisPermutation.of(Face.E, Face.U, Face.S), -102, 120, 0);
        Vec3 destination = ClientTravelMotion.point(Similarity.of(transform.inverse(), 1.0D), new Vec3(pose.x(), pose.y(), pose.z()));
        assertEquals(200, pose.y(), 0);
        assertEquals(80, destination.y, 0);
    }

    @Test
    public void fractionalInterpolatedFeetAreNotReconstructedFromTheEye() throws ReflectiveOperationException {
        Vec3 feet = new Vec3(1001.3125, 200.375, 0.4375);
        TravelMessage.TravelPose pose = pose(feet, 0.375f);
        assertEquals(feet.x, pose.x(), 0);
        assertEquals(feet.y, pose.y(), 0);
        assertEquals(feet.z, pose.z(), 0);
        assertEquals(180, pose.yaw(), 0);
        assertEquals(15, pose.pitch(), 0);
    }

    @Test
    public void titleFrameDoesNotReadUninitializedCameraInterpolation() {
        Minecraft minecraft = mock(Minecraft.class);
        Camera camera = mock(Camera.class);
        DeltaTracker tracker = mock(DeltaTracker.class);
        ClientPreparedTravel travel = ClientTravelTestFixtures.travel(ignored -> { });
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            assertFalse(travel.beforeFrame(camera, tracker));
        }
        verifyNoInteractions(camera, tracker);
    }

    @Test
    public void detachedPlayerDoesNotReadUninitializedCameraInterpolation() {
        Minecraft minecraft = mock(Minecraft.class);
        minecraft.player = mock(LocalPlayer.class);
        Camera camera = mock(Camera.class);
        DeltaTracker tracker = mock(DeltaTracker.class);
        ClientPreparedTravel travel = ClientTravelTestFixtures.travel(ignored -> { });
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            assertFalse(travel.beforeFrame(camera, tracker));
        }
        verifyNoInteractions(camera, tracker);
    }

    @Test
    public void joiningWorldDoesNotReadCameraInterpolationBeforeSetup() {
        Minecraft minecraft = mock(Minecraft.class);
        minecraft.player = mock(LocalPlayer.class);
        minecraft.level = mock(ClientLevel.class);
        Camera camera = mock(Camera.class);
        DeltaTracker tracker = mock(DeltaTracker.class);
        ClientPreparedTravel travel = ClientTravelTestFixtures.travel(ignored -> { });
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            assertFalse(travel.beforeFrame(camera, tracker));
        }
        verify(camera, never()).getCameraEntityPartialTicks(tracker);
        verifyNoInteractions(tracker);
    }

    @Test
    public void frameRuleMatchesThePlainTransformOnCurrentAndPreviousFields() {
        TravelMessage.TravelBegin begin = begin(Face.E, OrientationRule.FRAME, false, PRESERVE);
        Pose arrived = ClientTravelMotion.arrive(begin, SOURCE, CROSSING);
        Pose plain = PoseTransform.apply(SOURCE, begin.destinationToSource().inverse());
        assertLook(Angles.direction(plain.yaw(), plain.pitch()), arrived.yaw(), arrived.pitch());
        assertLook(Angles.direction(plain.previousYaw(), plain.previousPitch()), arrived.previousYaw(), arrived.previousPitch());
        assertEquals(plain.bodyYaw(), arrived.bodyYaw(), 0.001F);
        assertEquals(plain.previousHeadYaw(), arrived.previousHeadYaw(), 0.001F);
        assertEquals(plain.position(), arrived.position());
        assertEquals(plain.previousPosition(), arrived.previousPosition());
        assertEquals(plain.oldPosition(), arrived.oldPosition());
        assertEquals(new Vec3d(-1, 0, 0), new Vec3d(Math.round(Angles.direction(arrived.yaw(), 0).x()), 0, Math.round(Angles.direction(arrived.yaw(), 0).z())));
    }

    @Test
    public void lookRuleKeepsTheWorldLookOnCurrentAndPreviousFields() {
        Pose arrived = ClientTravelMotion.arrive(begin(Face.E, OrientationRule.LOOK, true, PRESERVE), SOURCE, CROSSING);
        assertLook(Angles.direction(SOURCE.yaw(), SOURCE.pitch()), arrived.yaw(), arrived.pitch());
        assertLook(Angles.direction(SOURCE.previousYaw(), SOURCE.previousPitch()), arrived.previousYaw(), arrived.previousPitch());
        assertLook(Angles.direction(SOURCE.bodyYaw(), 0), arrived.bodyYaw(), 0);
        assertLook(Angles.direction(SOURCE.previousHeadYaw(), SOURCE.previousPitch()), arrived.previousHeadYaw(), SOURCE.previousPitch());
    }

    @Test
    public void snapRuleFacesOutOfTheExitOnCurrentAndPreviousFields() {
        Pose arrived = ClientTravelMotion.arrive(begin(Face.E, OrientationRule.SNAP, false, PRESERVE), SOURCE, CROSSING);
        assertLook(new Vec3d(-1, 0, 0), arrived.yaw(), arrived.pitch());
        assertLook(new Vec3d(-1, 0, 0), arrived.previousYaw(), arrived.previousPitch());
        assertEquals(arrived.yaw(), arrived.bodyYaw(), 0.001F);
        assertEquals(arrived.yaw(), arrived.headYaw(), 0.001F);
        assertEquals(arrived.previousYaw(), arrived.previousBodyYaw(), 0.001F);
    }

    @Test
    public void mirrorRuleReflectsTheFrameLookAboutTheExitNormal() {
        TravelMessage.TravelBegin begin = begin(Face.E, OrientationRule.MIRROR, false, PRESERVE);
        Pose arrived = ClientTravelMotion.arrive(begin, SOURCE, CROSSING);
        Pose plain = PoseTransform.apply(SOURCE, begin.destinationToSource().inverse());
        Vec3d normal = new Vec3d(1, 0, 0);
        assertLook(Angles.reflect(Angles.direction(plain.yaw(), plain.pitch()), normal), arrived.yaw(), arrived.pitch());
        assertLook(Angles.reflect(Angles.direction(plain.previousYaw(), plain.previousPitch()), normal), arrived.previousYaw(),
            arrived.previousPitch());
    }

    @Test
    public void gravityFlipKeepsVerticalExitsUprightOnCurrentAndPreviousFields() {
        TravelMessage.TravelBegin flipped = begin(Face.U, OrientationRule.FRAME, true, PRESERVE);
        TravelMessage.TravelBegin plain = begin(Face.U, OrientationRule.FRAME, false, PRESERVE);
        Pose arrived = ClientTravelMotion.arrive(flipped, SOURCE, CROSSING);
        Pose unflipped = ClientTravelMotion.arrive(plain, SOURCE, CROSSING);
        Frame exit = Frame.canonical(Face.U);
        Frame source = ClientTravelTestFixtures.geometry().frame();
        assertLook(ArrivalOrientation.direction(crossing(source, SOURCE.yaw(), SOURCE.pitch()), exit, OrientationRule.FRAME, true),
            arrived.yaw(), arrived.pitch());
        assertLook(ArrivalOrientation.direction(crossing(source, SOURCE.previousYaw(), SOURCE.previousPitch()), exit, OrientationRule.FRAME, true),
            arrived.previousYaw(), arrived.previousPitch());
        assertTrue(Math.abs(Angles.direction(arrived.yaw(), arrived.pitch()).y() - Angles.direction(unflipped.yaw(), unflipped.pitch()).y()) > 0.5D);
    }

    @Test
    public void momentumRulesShapeThePredictedVelocity() {
        Vec3d mapped = PoseTransform.apply(SOURCE, begin(Face.E, OrientationRule.FRAME, false, PRESERVE).destinationToSource().inverse()).velocity();
        assertEquals(mapped, ClientTravelMotion.arrive(begin(Face.E, OrientationRule.FRAME, false, PRESERVE), SOURCE, CROSSING).velocity());
        assertVector(mapped.multiply(0.5D), ClientTravelMotion.arrive(begin(Face.E, OrientationRule.FRAME, false,
            new MomentumRule(MomentumRule.Mode.SCALE, 0.5D, 0.0D, null)), SOURCE, CROSSING).velocity());
        assertVector(new Vec3d(0, 0, 0), ClientTravelMotion.arrive(begin(Face.E, OrientationRule.FRAME, false,
            new MomentumRule(MomentumRule.Mode.ZERO, 1.0D, 0.0D, null)), SOURCE, CROSSING).velocity());
        assertVector(mapped.add(new Vec3d(0, 1, 0)), ClientTravelMotion.arrive(begin(Face.E, OrientationRule.FRAME, false,
            new MomentumRule(MomentumRule.Mode.IMPULSE, 1.0D, 0.0D, new Vec3d(0, 1, 0))), SOURCE, CROSSING).velocity());
        assertEquals(0.01D, ClientTravelMotion.arrive(begin(Face.E, OrientationRule.FRAME, false,
            new MomentumRule(MomentumRule.Mode.CLAMP, 1.0D, 0.1D, null)), SOURCE, CROSSING).velocity().lengthSquared(), 0.000001D * 0.01D);
    }

    @Test
    public void exitFrameIsTheSourceViewCarriedByTheCrossingTransform() {
        TravelMessage.TravelBegin begin = begin(Face.E, OrientationRule.FRAME, false, PRESERVE);
        Frame exit = ClientTravelMotion.exitFrame(begin.sourceGeometry().frame().view(true), begin.destinationToSource().inverse(), true);
        assertEquals(Frame.canonical(Face.E), exit);
    }

    private static TravelMessage.TravelBegin begin(Face exit, OrientationRule orientation, boolean gravityFlip, MomentumRule momentum) {
        Frame destination = Frame.canonical(exit);
        Frame source = ClientTravelTestFixtures.geometry().frame();
        OpticTransform destinationToSource = OpticTransform.between(destination, new Vec3d(100, 64, 100), source, new Vec3d(0, 0, 0));
        return new TravelMessage.TravelBegin(new UUID(1, 2), 3, new UUID(4, 5), "minecraft:overworld", ClientTravelTestFixtures.geometry(),
            destinationToSource, 1.0F, new TravelMessage.TravelWorld("minecraft:overworld", "minecraft:overworld", 7, false, false, 63, -64, 384),
            new TravelMessage.TravelPose(100, 64, 100, 0, 0), List.of(new TravelMessage.TravelCoordinate(6, 6)),
            PortalEnvironmentTest.environment(OpticTransform.IDENTITY), 30_000,
            new TravelMessage.ArrivalRules(orientation, gravityFlip, momentum, ScaleRule.OFF), true, 1, true);
    }

    private static PlaneCrossing crossing(Frame source, float yaw, float pitch) {
        return new PlaneCrossing(source.view(true), CROSSING, CROSSING, SOURCE.velocity(), Angles.direction(yaw, pitch), true);
    }

    private static void assertLook(Vec3d expected, float yaw, float pitch) {
        Vec3d actual = Angles.direction(yaw, pitch);
        assertTrue("expected look " + expected + " but was " + actual, actual.subtract(expected.normalize()).lengthSquared() < 0.0005D * 0.0005D);
    }

    private static void assertVector(Vec3d expected, Vec3d actual) {
        assertTrue("expected " + expected + " but was " + actual, actual.subtract(expected).lengthSquared() < 0.000001D * 0.000001D);
    }

    private static TravelMessage.TravelPose pose(Vec3 feet, float partial) throws ReflectiveOperationException {
        LocalPlayer player = mock(LocalPlayer.class);
        when(player.getPosition(partial)).thenReturn(feet);
        when(player.getYRot()).thenReturn(180f);
        when(player.getXRot()).thenReturn(15f);
        Method method = ClientPreparedTravel.class.getDeclaredMethod("crossingPose", LocalPlayer.class, float.class);
        method.setAccessible(true);
        return (TravelMessage.TravelPose) method.invoke(null, player, partial);
    }
}
