package art.arcane.wormholes.modded.client.render.dh;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.client.render.iris.IrisClipPlanes;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import org.joml.Matrix4f;
import org.joml.Vector4d;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.junit.Test;
import org.mockito.MockedStatic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

public class DhBlazeClipProgramsTest {
    @Test
    public void blazeClipSpacePlaneMatchesRawDhClippingWithRotatedProjection() {
        Matrix4f combined = new Matrix4f().perspective((float) Math.toRadians(70.0), 1.5F, 8.0F, 4096.0F)
            .rotateY(0.6F).rotateX(-0.2F);
        DhApiRenderParam parameters = new DhApiRenderParam();
        parameters.dhInverseMvmProjectionMatrix.set(new DhMat4f(combined.invert(new Matrix4f())));
        Vector4f relative = DhClipPrograms.relativePlane(new Vector4d(1.0, 0.0, 0.0, -10_000_000.0),
            new Vec3d(10_000_000.125, 80.5, -10_000_000.75));
        Vector4f clipPlane = DhBlazeClipPrograms.clipPlane(parameters, relative, new Vector4f());
        for (float x : new float[]{-16.0F, -0.5F, 0.5F, 16.0F}) {
            Vector4f position = new Vector4f(x, 2.0F, -128.0F, 1.0F);
            Vector4f projected = combined.transform(position, new Vector4f());
            assertEquals(relative.dot(position), clipPlane.dot(projected), 0.0001F);
        }
    }

    @Test
    public void activeNestedPortalPlaneAndOrdinaryPassAreDistinct() {
        DhApiRenderParam parameters = new DhApiRenderParam();
        parameters.dhInverseMvmProjectionMatrix.set(new DhMat4f(new Matrix4f()));
        try (MockedStatic<IrisClipPlanes> clipping = mockStatic(IrisClipPlanes.class)) {
            clipping.when(IrisClipPlanes::capture).thenReturn(mock(IrisClipPlanes.State.class));
            try (DhClipPrograms.Scope outer = DhClipPrograms.open(new Vector4d(1.0, 0.0, 0.0, -10.0), new Vec3d(11.0, 0.0, 0.0))) {
                Vector4fc relative = DhClipPrograms.plane();
                assertEquals(1.0F, DhBlazeClipPrograms.clipPlane(parameters, relative, new Vector4f()).w, 0.0F);
                try (DhClipPrograms.Scope inner = DhClipPrograms.open(new Vector4d(0.0, 0.0, 1.0, -5.0), new Vec3d(0.0, 0.0, 7.0))) {
                    assertEquals(2.0F, DhBlazeClipPrograms.clipPlane(parameters, DhClipPrograms.plane(), new Vector4f()).w, 0.0F);
                }
                assertEquals(1.0F, DhBlazeClipPrograms.clipPlane(parameters, DhClipPrograms.plane(), new Vector4f()).w, 0.0F);
            }
        }
        assertTrue(DhBlazeClipPrograms.clipPlane(parameters, DhClipPrograms.plane(), new Vector4f()).equals(new Vector4f(0.0F)));
    }
}
