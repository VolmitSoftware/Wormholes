package art.arcane.wormholes.modded.client.render.stencil;

import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PortalCullingFrustumTest {
    @Test(timeout = 3000L)
    public void cameraCubeOffsetTerminatesForEveryRotatedOrReflectedPortalView() {
        for (boolean zeroToOne : new boolean[]{false, true}) {
            Matrix4f render = new Matrix4f().setPerspective((float) Math.toRadians(70.0D), 16.0F / 9.0F,
                1024.0F, 0.05F, zeroToOne);
            for (int yaw = 0; yaw < 360; yaw += 45) {
                for (int pitch = -60; pitch <= 60; pitch += 30) {
                    for (boolean mirrored : new boolean[]{false, true}) {
                        Matrix4f view = new Matrix4f().rotationXYZ((float) Math.toRadians(pitch),
                            (float) Math.toRadians(yaw), 0.0F);
                        if (mirrored) {
                            view.scale(-1.0F, 1.0F, 1.0F);
                        }
                        Frustum frustum = new Frustum(view, PortalLayerMath.cullingProjection(render, zeroToOne));
                        frustum.prepare(12.3D, 70.6D, -5.2D);

                        Frustum offset = new Frustum(frustum).offsetToFullyIncludeCameraCube(8);

                        assertTrue(Double.isFinite(offset.getCamX()));
                        assertTrue(Double.isFinite(offset.getCamY()));
                        assertTrue(Double.isFinite(offset.getCamZ()));
                        assertEquals(12.3D, frustum.getCamX(), 0.0D);
                        assertEquals(70.6D, frustum.getCamY(), 0.0D);
                        assertEquals(-5.2D, frustum.getCamZ(), 0.0D);
                    }
                }
            }
        }
    }

    @Test
    public void cullingProjectionRestoresConventionalDepthWithoutChangingScreenCoordinates() {
        for (boolean zeroToOne : new boolean[]{false, true}) {
            Matrix4f render = new Matrix4f().setPerspective((float) Math.toRadians(70.0D), 16.0F / 9.0F,
                1024.0F, 0.05F, zeroToOne);
            Matrix4f expected = new Matrix4f().setPerspective((float) Math.toRadians(70.0D), 16.0F / 9.0F,
                0.05F, 1024.0F, zeroToOne);

            assertTrue(expected.equals(PortalLayerMath.cullingProjection(render, zeroToOne), 1.0E-6F));
        }
    }
}
