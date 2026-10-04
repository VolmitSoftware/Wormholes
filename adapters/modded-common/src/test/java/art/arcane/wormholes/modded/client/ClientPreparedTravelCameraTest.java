package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.network.client.ClientViewMessage;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector3f;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;

public class ClientPreparedTravelCameraTest {
    @Test
    public void arrivalCameraMatchesNativeForwardRightAndUpAtBothYawAndPitchSigns() throws ReflectiveOperationException {
        Method method = ClientPreparedTravel.class.getDeclaredMethod("arrivalCamera", ClientViewMessage.TravelPose.class);
        method.setAccessible(true);
        for (float yaw : new float[]{0, 90}) {
            for (float pitch : new float[]{-15, 0, 15}) {
                CameraRenderState camera = (CameraRenderState) method.invoke(null, new ClientViewMessage.TravelPose(0, 80, 0, yaw, pitch));
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

    private static void vector(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x, actual.x, 0.000001);
        assertEquals(expected.y, actual.y, 0.000001);
        assertEquals(expected.z, actual.z, 0.000001);
    }
}
