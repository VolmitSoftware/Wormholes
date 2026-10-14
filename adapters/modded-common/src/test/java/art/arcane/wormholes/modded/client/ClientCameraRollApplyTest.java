package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.mixin.client.CameraPoseAccess;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class ClientCameraRollApplyTest extends MinecraftTestBase {
    @Test
    public void applyingTheRollReorientsTheWholeCameraAndItsCullingView() {
        Quaternionf rotation = new Quaternionf().rotationYXZ((float) Math.toRadians(150.0F), (float) Math.toRadians(-20.0F), 0.0F);
        Quaternionf level = new Quaternionf(rotation);
        Camera camera = mock(Camera.class, withSettings().extraInterfaces(CameraPoseAccess.class, GravityCameraAccess.class));
        CameraPoseAccess pose = (CameraPoseAccess) camera;
        Matrix4f projection = new Matrix4f().perspective(1.2F, 16.0F / 9.0F, 0.05F, 512.0F);
        Vec3 position = new Vec3(3.0D, 70.0D, -4.0D);
        when(camera.rotation()).thenReturn(rotation);
        when(camera.position()).thenReturn(position);
        when(camera.getViewRotationMatrix(any(Matrix4f.class))).thenAnswer(invocation ->
            invocation.<Matrix4f>getArgument(0).rotation(new Quaternionf(rotation).conjugate()));
        when(pose.wormholes$cullingProjection()).thenReturn(projection);
        doAnswer(invocation -> {
            rotation.set(invocation.<Quaternionf>getArgument(0));
            return null;
        }).when((GravityCameraAccess) camera).wormholes$orientation(any(Quaternionf.class));
        ClientCameraRoll roll = new ClientCameraRoll();
        roll.start(90.0F, 0.35D, 1_000L);

        roll.apply(camera, 1_000L);

        Quaternionf expected = new Quaternionf(level).rotateZ((float) Math.toRadians(-90.0F));
        assertBasis(expected, rotation);
        ArgumentCaptor<Matrix4fc> view = ArgumentCaptor.forClass(Matrix4fc.class);
        verify(pose).wormholes$cullFrustum(view.capture(), any(Matrix4f.class), any(Vec3.class));
        Vector3f up = view.getValue().positiveY(new Vector3f());
        Vector3f expectedUp = new Vector3f(0.0F, 1.0F, 0.0F).rotate(expected);
        assertEquals(expectedUp.x, up.x, 1.0E-5F);
        assertEquals(expectedUp.y, up.y, 1.0E-5F);
        assertEquals(expectedUp.z, up.z, 1.0E-5F);
        verify(pose).wormholes$cullFrustum(any(Matrix4fc.class), any(Matrix4f.class), eq(position));
    }

    private static void assertBasis(Quaternionf expected, Quaternionf actual) {
        for (Vector3f axis : new Vector3f[] {new Vector3f(0.0F, 0.0F, -1.0F), new Vector3f(0.0F, 1.0F, 0.0F), new Vector3f(-1.0F, 0.0F, 0.0F)}) {
            Vector3f wanted = new Vector3f(axis).rotate(expected);
            Vector3f observed = new Vector3f(axis).rotate(actual);
            assertEquals(wanted.x, observed.x, 1.0E-5F);
            assertEquals(wanted.y, observed.y, 1.0E-5F);
            assertEquals(wanted.z, observed.z, 1.0E-5F);
        }
    }
}
