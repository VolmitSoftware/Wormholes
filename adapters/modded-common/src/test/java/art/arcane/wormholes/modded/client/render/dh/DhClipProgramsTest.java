package art.arcane.wormholes.modded.client.render.dh;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.client.render.iris.IrisClipPlanes;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import org.joml.Matrix4f;
import org.joml.Vector4d;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mockStatic;

public class DhClipProgramsTest {
    @Test
    public void relativePlaneRetainsSubBlockPrecisionFarFromOrigin() {
        Vector4f plane = DhClipPrograms.relativePlane(new Vector4d(1.0, 0.0, 0.0, -10_000_000.0),
            new Vec3d(10_000_000.125, 80.5, -10_000_000.75));

        assertEquals(1.0F, plane.x, 0.0F);
        assertEquals(0.125F, plane.w, 0.0F);
        assertEquals(-0.375F, plane.dot(new Vector4f(-0.5F, 0.0F, 0.0F, 1.0F)), 0.0F);
        assertEquals(0.625F, plane.dot(new Vector4f(0.5F, 0.0F, 0.0F, 1.0F)), 0.0F);
    }

    @Test
    public void dhRowMajorMatrixMapsToJomlWithoutChangingCoordinates() {
        DhApiMat4f dh = new DhApiMat4f();
        dh.m00 = 2.0F;
        dh.m11 = 3.0F;
        dh.m22 = 4.0F;
        dh.m33 = 1.0F;
        dh.m03 = 7.0F;
        dh.m13 = -5.0F;
        dh.m23 = 11.0F;
        Matrix4f mapped = DhClipPrograms.matrix(dh, new Matrix4f());
        Vector4f point = mapped.transform(new Vector4f(1.0F, -1.0F, 3.0F, 1.0F));

        assertEquals(9.0F, point.x, 0.0F);
        assertEquals(-8.0F, point.y, 0.0F);
        assertEquals(23.0F, point.z, 0.0F);
        assertEquals(1.0F, point.w, 0.0F);
        assertEquals(7.0F, dh.m03, 0.0F);
    }

    @Test
    public void nestedScopesRestoreTheirParentAndRejectOutOfOrderClose() {
        IrisClipPlanes.State outerState = mock(IrisClipPlanes.State.class);
        IrisClipPlanes.State innerState = mock(IrisClipPlanes.State.class);
        assertNull(DhClipPrograms.plane());
        try (MockedStatic<IrisClipPlanes> clipping = mockStatic(IrisClipPlanes.class)) {
            clipping.when(IrisClipPlanes::capture).thenReturn(outerState, innerState);
            try (DhClipPrograms.Scope outer = DhClipPrograms.open(new Vector4d(1.0, 0.0, 0.0, -10.0), new Vec3d(11.0, 0.0, 0.0))) {
                Vector4fc parent = DhClipPrograms.plane();
                assertEquals(1.0F, parent.w(), 0.0F);
                try (DhClipPrograms.Scope inner = DhClipPrograms.open(new Vector4d(0.0, 0.0, 1.0, -5.0), new Vec3d(0.0, 0.0, 7.0))) {
                    assertEquals(2.0F, DhClipPrograms.plane().w(), 0.0F);
                    assertThrows(IllegalStateException.class, outer::close);
                    verify(outerState, never()).restore();
                    verify(innerState, never()).restore();
                }
                assertSame(parent, DhClipPrograms.plane());
                verify(innerState).restore();
                verify(outerState, never()).restore();
            }
            clipping.verify(IrisClipPlanes::capture, times(2));
            InOrder restored = inOrder(innerState, outerState);
            restored.verify(innerState).restore();
            restored.verify(outerState).restore();
        }
        assertNull(DhClipPrograms.plane());
    }

    @Test
    public void failedNestedLayerRestoresClipSnapshotsOnceInParentOrder() {
        IrisClipPlanes.State outerState = mock(IrisClipPlanes.State.class);
        IrisClipPlanes.State innerState = mock(IrisClipPlanes.State.class);
        try (MockedStatic<IrisClipPlanes> clipping = mockStatic(IrisClipPlanes.class)) {
            clipping.when(IrisClipPlanes::capture).thenReturn(outerState, innerState);
            DhClipPrograms.Scope outer = DhClipPrograms.open(new Vector4d(1.0, 0.0, 0.0, -5.0), Vec3d.ZERO);
            try (outer) {
                Vector4fc parent = DhClipPrograms.plane();
                assertThrows(IllegalStateException.class, () -> {
                    try (DhClipPrograms.Scope inner = DhClipPrograms.open(new Vector4d(0.0, 0.0, 1.0, -5.0), Vec3d.ZERO)) {
                        throw new IllegalStateException("layer failure");
                    }
                });
                assertSame(parent, DhClipPrograms.plane());
                verify(innerState).restore();
                verify(outerState, never()).restore();
            }
            assertNull(DhClipPrograms.plane());
            outer.close();
            InOrder restored = inOrder(innerState, outerState);
            restored.verify(innerState).restore();
            restored.verify(outerState).restore();
            verify(innerState, times(1)).restore();
            verify(outerState, times(1)).restore();
        }
    }
}
