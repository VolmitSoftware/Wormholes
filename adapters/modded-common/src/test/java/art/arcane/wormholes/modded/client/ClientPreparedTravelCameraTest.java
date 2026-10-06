package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.math.Face;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import org.mockito.MockedStatic;
import org.junit.BeforeClass;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector3f;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;

public class ClientPreparedTravelCameraTest {
    @BeforeClass
    public static void bootstrapMinecraft() {
        MinecraftTestBase.bootstrap();
    }

    @Test
    public void arrivalCameraMatchesNativeForwardRightAndUpAtBothYawAndPitchSigns() throws ReflectiveOperationException {
        for (float yaw : new float[]{0, 90}) {
            for (float pitch : new float[]{-15, 0, 15}) {
                CameraRenderState camera = ClientPreparedTravel.arrivalCamera(new ClientViewMessage.TravelPose(0, 80, 0, yaw, pitch),
                    EntityTypes.PLAYER.getDimensions().eyeHeight());
                double y = Math.toRadians(yaw);
                double p = Math.toRadians(pitch);
                Vector3f forward = new Vector3f((float) (-Math.sin(y) * Math.cos(p)), (float) -Math.sin(p),
                    (float) (Math.cos(y) * Math.cos(p)));
                Vector3f right = new Vector3f((float) -Math.cos(y), 0, (float) -Math.sin(y));
                Vector3f up = new Vector3f(right).cross(forward);
                vector(new Vector3f(0, 0, -1), camera.viewRotationMatrix.transformDirection(forward));
                vector(new Vector3f(1, 0, 0), camera.viewRotationMatrix.transformDirection(right));
                vector(new Vector3f(0, 1, 0), camera.viewRotationMatrix.transformDirection(up));
            }
        }
    }

    @Test
    public void arrivalCameraUsesExactNativeStandingCrouchingAndSwimmingEyeHeights() {
        LocalPlayer player = mock(LocalPlayer.class);
        when(player.getDefaultDimensions(any())).thenCallRealMethod();
        for (Pose pose : new Pose[]{Pose.STANDING, Pose.CROUCHING, Pose.SWIMMING}) {
            float height = player.getDefaultDimensions(pose).eyeHeight();
            CameraRenderState camera = ClientPreparedTravel.arrivalCamera(new ClientViewMessage.TravelPose(1.5, 80, -3.5, 0, 0), height);
            assertEquals(new Vec3(1.5, 80 + (double) height, -3.5), camera.pos);
        }
    }

    @Test
    public void currentTravelCameraMapsOnlyFeetAndLookAndKeepsPostureUprightAcrossRotatedAxes() throws ReflectiveOperationException {
        ClientViewMessage.TravelBegin original = begin();
        Minecraft minecraft = mock(Minecraft.class);
        minecraft.level = mock(ClientLevel.class);
        when(minecraft.level.dimension()).thenReturn(Level.NETHER);
        LocalPlayer player = mock(LocalPlayer.class);
        minecraft.player = player;
        when(player.getX()).thenReturn(102.0);
        when(player.getY()).thenReturn(23.0);
        when(player.getZ()).thenReturn(206.0);
        when(player.getYRot()).thenReturn(30.0F);
        when(player.getXRot()).thenReturn(20.0F);
        when(player.getDefaultDimensions(any())).thenCallRealMethod();
        Method method = ClientPreparedTravel.class.getDeclaredMethod("travelCamera", ClientViewMessage.TravelBegin.class);
        method.setAccessible(true);
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            for (ProjectionEnvironment.Transform transform : new ProjectionEnvironment.Transform[]{
                new ProjectionEnvironment.Transform(Face.S, Face.U, Face.W, new art.arcane.optics.math.Vec3(100, 20, 200)),
                new ProjectionEnvironment.Transform(Face.E, Face.S, Face.D, new art.arcane.optics.math.Vec3(100, 20, 200))}) {
                ClientViewMessage.TravelBegin begin = new ClientViewMessage.TravelBegin(original.token(), original.generation(),
                    original.sourcePortal(), original.sourceWorld(), original.sourceGeometry(), transform,
                    original.world(), original.arrival(), original.chunks(), original.environment(), original.expiresMillis());
                for (Pose pose : new Pose[]{Pose.STANDING, Pose.CROUCHING, Pose.SWIMMING}) {
                    float height = player.getDefaultDimensions(pose).eyeHeight();
                    when(player.getEyeHeight()).thenReturn(height);
                    CameraRenderState camera = (CameraRenderState) method.invoke(null, begin);
                    art.arcane.optics.math.Vec3 feet = transform.destinationPoint(102, 23, 206);
                    ClientTravelMotion.Rotation look = new ClientTravelMotion.Rotation(30, 20).transform(transform);
                    assertEquals(new Vec3(feet.x(), feet.y() + height, feet.z()), camera.pos);
                    assertEquals(look.yaw(), camera.yRot, 0.0F);
                    assertEquals(look.pitch(), camera.xRot, 0.0F);
                }
            }
        }
        verify(player, never()).getDeltaMovement();
        verify(player, never()).oldPosition();
    }

    @Test
    public void unavailableOrForeignSourceUsesStoredArrivalAndCurrentOrNativeFallbackEyeHeight() throws ReflectiveOperationException {
        ClientViewMessage.TravelBegin begin = begin();
        Method method = ClientPreparedTravel.class.getDeclaredMethod("travelCamera", ClientViewMessage.TravelBegin.class);
        method.setAccessible(true);
        Minecraft minecraft = mock(Minecraft.class);
        LocalPlayer player = mock(LocalPlayer.class);
        when(player.getDefaultDimensions(any())).thenCallRealMethod();
        float crouching = player.getDefaultDimensions(Pose.CROUCHING).eyeHeight();
        when(player.getEyeHeight()).thenReturn(crouching);
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            for (int state = 0; state < 3; state++) {
                minecraft.player = state == 0 ? null : player;
                minecraft.level = state == 2 ? mock(ClientLevel.class) : null;
                if (minecraft.level != null) {
                    when(minecraft.level.dimension()).thenReturn(Level.OVERWORLD);
                }
                CameraRenderState camera = (CameraRenderState) method.invoke(null, begin);
                float height = state == 0 ? EntityTypes.PLAYER.getDimensions().eyeHeight() : crouching;
                assertEquals(new Vec3(begin.arrival().x(), begin.arrival().y() + height, begin.arrival().z()), camera.pos);
                assertEquals(begin.arrival().yaw(), camera.yRot, 0.0F);
                assertEquals(begin.arrival().pitch(), camera.xRot, 0.0F);
            }
        }
        verify(player, never()).getX();
        verify(player, never()).getYRot();
    }

    private static ClientViewMessage.TravelBegin begin() throws ReflectiveOperationException {
        Method method = ClientPreparedTravelPendingTest.class.getDeclaredMethod("begin", long.class);
        method.setAccessible(true);
        return (ClientViewMessage.TravelBegin) method.invoke(null, 1L);
    }

    private static void vector(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x, actual.x, 0.000001);
        assertEquals(expected.y, actual.y, 0.000001);
        assertEquals(expected.z, actual.z, 0.000001);
    }
}
