package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.aperture.AperturePolygon;
import art.arcane.optics.math.Axis;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.FitMode;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.optics.shape.ShapeMesh;
import art.arcane.optics.shape.Shapes;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.transform.Affine;
import art.arcane.wormholes.modded.MinecraftTestBase;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AffinePortalSurfaceTest extends MinecraftTestBase {
    private static final ShapeDescriptor RING = ShapeDescriptor.of(Shapes.ring(1.0D, 0.4D), FitMode.CONTAIN);

    @Test
    public void movingAndSpinningAperturesReuseTheirLocalShapeAndKeepTheHole() {
        AffinePortalSurface first = new AffinePortalSurface(sample(1L, Affine.IDENTITY, RING), null);
        ShapeMesh mesh = first.localShape().mesh(8, first.localGeometry().apertureMask());
        Affine moved = Affine.translation(12.0D, 6.0D, -3.0D).compose(Affine.rotation(new Vec3d(0, 0, 1), 0.7D));
        AffinePortalSurface next = new AffinePortalSurface(sample(2L, moved, RING), first);

        assertSame(first.localShape(), next.localShape());
        assertSame(first.localGeometry(), next.localGeometry());
        assertFalse(first.localShape().contains(4.0D, 4.0D));
        assertTrue(first.localShape().contains(7.0D, 4.0D));
        assertTrue(mesh.triangleCount() > 0);
        Vector3d point = next.meshToSource().transformPosition(new Vector3d(4.0D, 4.0D, 0.0D));
        assertEquals(12.0D, point.x, 1.0E-9D);
        assertEquals(6.0D, point.y, 1.0E-9D);
        assertEquals(-3.0D, point.z, 1.0E-9D);
    }

    @Test
    public void changingShapeReplacesOnlyTheLocalAperture() {
        AffinePortalSurface first = new AffinePortalSurface(sample(1L, Affine.IDENTITY, RING), null);
        ShapeDescriptor circle = ShapeDescriptor.of(Shapes.circle(1.0D), FitMode.CONTAIN);
        AffinePortalSurface next = new AffinePortalSurface(sample(2L, Affine.IDENTITY, circle), first);

        assertNotSame(first.localShape(), next.localShape());
        assertTrue(next.localShape().contains(4.0D, 4.0D));
    }

    @Test
    public void movingChildApertureReleasesItsStaleParentClipWithoutReplacingTheShape() throws Exception {
        AffinePortalSurface surface = new AffinePortalSurface(sample(1L, Affine.IDENTITY, RING), null);
        PortalScene scene = mock(PortalScene.class);
        when(scene.geometry()).thenReturn(surface.localGeometry());
        when(scene.affine()).thenReturn(surface.sample());
        Class<?> type = Class.forName(ClientPortalRenderer.class.getName() + "$Portal");
        Constructor<?> constructor = type.getDeclaredConstructor(int.class, PortalScene.class);
        constructor.setAccessible(true);
        Object portal = constructor.newInstance(1, scene);
        Method update = type.getDeclaredMethod("updateAffine");
        update.setAccessible(true);
        update.invoke(portal);
        Field affine = type.getDeclaredField("affine");
        affine.setAccessible(true);
        AffinePortalSurface previous = (AffinePortalSurface) affine.get(portal);
        Field clip = type.getDeclaredField("parentClip");
        clip.setAccessible(true);
        AutoCloseable buffer = (AutoCloseable) mock(clip.getType());
        clip.set(portal, buffer);
        when(scene.affine()).thenReturn(sample(2L, Affine.translation(2, 0, 0), RING));
        update.invoke(portal);
        verify(buffer).close();
        assertNull(clip.get(portal));
        assertSame(previous.localShape(), ((AffinePortalSurface) affine.get(portal)).localShape());
    }

    @Test
    public void tiltedShearedPlaneUsesInverseTransposeAndBoundsAllFourCorners() {
        Affine pose = Affine.translation(10.0D, 2.0D, -4.0D).compose(Affine.rotation(new Vec3d(0, 1, 0), 0.8D))
            .compose(Affine.shear(0.4D, 0, 0, 0, 0, 0)).compose(Affine.scale(2.0D, 0.7D, 1.0D));
        AffinePortalSurface surface = new AffinePortalSurface(sample(1L, pose, RING), null);
        AperturePolygon.Plane plane = surface.plane();
        for (Vec3d local : new Vec3d[] {new Vec3d(-1, -1, 0), new Vec3d(1, -1, 0), new Vec3d(-1, 1, 0), new Vec3d(1, 1, 0)}) {
            Vec3d point = pose.point(local);
            assertEquals(0.0D, plane.signedDistance(point), 1.0E-9D);
            assertTrue(surface.bounds().contains(point.x(), point.y(), point.z()));
        }
        Vec3d front = pose.point(new Vec3d(0, 0, 1));
        assertTrue(plane.signedDistance(front) > 0.0D);
    }

    @Test
    public void destinationDepthAndReflectionChangeCameraGeometryWithoutChangingTheGrid() {
        Affine view = Affine.translation(4, 0, 8).compose(Affine.scale(1, 1, 2)).compose(Affine.reflection(Axis.X));
        Matrix4d contentToSource = PortalProjection.matrix(view).invert();
        Vector3d destination = contentToSource.transformPosition(new Vector3d(2, 3, 4));
        Vec3d expected = view.inverse().point(new Vec3d(2, 3, 4));
        assertEquals(expected.x(), destination.x, 1.0E-9D);
        assertEquals(expected.y(), destination.y, 1.0E-9D);
        assertEquals(expected.z(), destination.z, 1.0E-9D);
        assertTrue(contentToSource.determinant3x3() < 0.0D);
        AperturePolygon.Plane source = new AperturePolygon.Plane(0, 0, 1, 0);
        AperturePolygon.Plane gridPlane = AffinePortalSurface.transformedPlane(source, contentToSource);
        Vec3d onPlane = view.point(new Vec3d(0.3D, 0.7D, 0));
        assertEquals(0.0D, gridPlane.signedDistance(onPlane), 1.0E-9D);
        assertEquals(1.0D, Math.sqrt(gridPlane.x() * gridPlane.x() + gridPlane.y() * gridPlane.y() + gridPlane.z() * gridPlane.z()), 1.0E-9D);
    }

    private static ViewStreamMessage.PortalAffine sample(long revision, Affine pose, ShapeDescriptor shape) {
        return new ViewStreamMessage.PortalAffine(1, revision, pose, Affine.IDENTITY, shape);
    }
}
