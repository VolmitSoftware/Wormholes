package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.optics.math.Face;
import art.arcane.optics.frame.OpticTransform;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import art.arcane.wormholes.network.client.TravelMessage;

public class ClientTravelCrossingPoseTest extends MinecraftTestBase {
    @Test
    public void standingReverseCrossingPreservesExactFeetAboveDestinationFloor() throws ReflectiveOperationException {
        TravelMessage.TravelPose pose = pose(new Vec3(1001.5, 200, 0.4), 0.75f);
        OpticTransform transform = OpticTransform.of(AxisPermutation.of(Face.E, Face.U, Face.S), -102, 120, 0);
        Vec3 destination = ClientTravelMotion.point(transform, new Vec3(pose.x(), pose.y(), pose.z()));
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
        ClientPreparedTravel travel = new ClientPreparedTravel(ignored -> { });
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
        ClientPreparedTravel travel = new ClientPreparedTravel(ignored -> { });
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
        ClientPreparedTravel travel = new ClientPreparedTravel(ignored -> { });
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            assertFalse(travel.beforeFrame(camera, tracker));
        }
        verify(camera, never()).getCameraEntityPartialTicks(tracker);
        verifyNoInteractions(tracker);
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
