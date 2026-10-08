package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.portal.ApertureKind;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4d;
import org.joml.Vector4f;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PortalLayerMathTest {
    private static final Vec3d EYE = new Vec3d(12.3D, 70.6D, -5.2D);
    private static final float YAW = 37.0F;
    private static final float PITCH = -12.0F;

    @Test
    public void innerCameraSeesEveryMappedPointWhereTheOuterCameraSeesTheSourcePoint() {
        Matrix4f projection = projection();
        Matrix4f outerView = view(YAW, PITCH);
        for (Similarity transform : transforms()) {
            Vec3d inner = PortalLayerMath.innerCamera(transform, EYE);
            Matrix4f innerView = PortalLayerMath.innerView(outerView, transform);
            for (Vec3d point : List.of(new Vec3d(14.0D, 71.0D, -12.0D), new Vec3d(8.5D, 66.0D, -20.0D), new Vec3d(20.0D, 75.5D, -9.0D))) {
                Vector4f outer = clip(projection, outerView, EYE, point);
                Vector4f mapped = clip(projection, innerView, inner, transform.point(point));
                assertEquals(outer.x / outer.w, mapped.x / mapped.w, 1.0E-4F);
                assertEquals(outer.y / outer.w, mapped.y / mapped.w, 1.0E-4F);
                assertEquals(Math.signum(outer.w), Math.signum(mapped.w), 0.0F);
            }
        }
    }

    @Test
    public void innerViewIsReflectedOnlyForReflectingTransforms() {
        Matrix4f outerView = view(YAW, PITCH);
        for (Similarity transform : transforms()) {
            Matrix4f inner = PortalLayerMath.innerView(outerView, transform);
            assertEquals(transform.rigid().reflects(), inner.determinant3x3() < 0.0F);
            assertEquals(transform.rigid().reflects(), PortalLayerMath.mirrored(transform));
        }
    }

    @Test
    public void billboardOrientationIsAProperRotationEvenThroughMirrors() {
        Matrix4f outerView = view(YAW, PITCH);
        for (Similarity transform : transforms()) {
            Quaternionf orientation = PortalLayerMath.orientation(PortalLayerMath.innerView(outerView, transform));
            assertEquals(1.0F, orientation.lengthSquared(), 1.0E-4F);
        }
    }

    @Test
    public void destinationPlaneKeepsOnlyWhatLiesBeyondTheExit() {
        PortalSurface surface = PortalSurface.of(portal(Face.S, true));
        for (Similarity transform : transforms()) {
            Vector4d plane = PortalLayerMath.destinationPlane(surface, transform, 0.0D);
            Vec3d inner = PortalLayerMath.innerCamera(transform, EYE);
            Vec3d beyond = transform.point(new Vec3d(3.5D, 66.0D, -30.0D));
            assertTrue(distance(plane, inner) < 0.0D);
            assertTrue(distance(plane, beyond) > 0.0D);
        }
    }

    @Test
    public void destinationPlaneMarginClipsSurfacesLyingInThePlane() {
        PortalSurface surface = PortalSurface.of(portal(Face.S, true));
        Similarity transform = Similarity.of(OpticTransform.of(AxisPermutation.of(Face.W, Face.U, Face.N), 400.0D, 10.0D, 90.0D), 1.0D);
        Vector4d plane = PortalLayerMath.destinationPlane(surface, transform, 0.01D);
        Vec3d inPlane = transform.point(surface.planePoint());
        assertTrue(distance(plane, inPlane) < 0.0D);
    }

    @Test
    public void clipSpacePlaneMeasuresTheSameDistanceAsTheWorldPlane() {
        Matrix4f projection = projection();
        Matrix4f outerView = view(YAW, PITCH);
        PortalSurface surface = PortalSurface.of(portal(Face.E, false));
        for (Similarity transform : transforms()) {
            Vec3d inner = PortalLayerMath.innerCamera(transform, EYE);
            Matrix4f innerView = PortalLayerMath.innerView(outerView, transform);
            Vector4d world = PortalLayerMath.destinationPlane(surface, transform, 0.0D);
            Vector4f clipPlane = PortalLayerMath.clipPlane(PortalLayerMath.viewPlane(world, inner, innerView), projection);
            for (Vec3d point : List.of(new Vec3d(inner.x() + 3.0D, inner.y() - 1.0D, inner.z() + 2.0D),
                new Vec3d(inner.x() - 5.0D, inner.y() + 2.0D, inner.z() - 7.0D))) {
                Vector4f clip = clip(projection, innerView, inner, point);
                assertEquals(distance(world, point), clipPlane.dot(clip), 1.0E-3D);
            }
        }
    }

    @Test
    public void surfaceServesItsFrontAndTheReturnSurfaceServesTheExitSide() {
        ApertureDescriptor geometry = portal(Face.S, true);
        PortalSurface forward = PortalSurface.of(geometry);
        assertTrue(forward.servesEye(new Vec3d(4.5D, 66.0D, 3.0D)));
        assertFalse(forward.servesEye(new Vec3d(4.5D, 66.0D, -9.0D)));
        Similarity transform = Similarity.of(OpticTransform.of(AxisPermutation.of(Face.N, Face.U, Face.E), -200.0D, 0.0D, 50.0D), 2.0D);
        PortalSurface back = forward.through(transform);
        Vec3d exit = transform.point(new Vec3d(4.5D, 66.0D, -9.0D));
        Vec3d entry = transform.point(new Vec3d(4.5D, 66.0D, 3.0D));
        assertTrue(back.servesEye(exit));
        assertFalse(back.servesEye(entry));
        assertTrue(back.signedDistance(exit) > 0.0D);
        assertEquals(transform.point(forward.planePoint()).distance(back.planePoint()), 0.0D, 1.0E-9D);
    }

    @Test
    public void surfaceStraddleNeedsTheBoundsToCrossThePlaneInsideTheOpening() {
        PortalSurface surface = PortalSurface.of(portal(Face.S, true));
        double plane = surface.planePoint().z();
        assertTrue(surface.straddles(new Box(4.2D, 4.8D, 65.0D, 66.8D, plane - 0.3D, plane + 0.3D)));
        assertFalse(surface.straddles(new Box(4.2D, 4.8D, 65.0D, 66.8D, plane + 0.1D, plane + 0.7D)));
        assertFalse(surface.straddles(new Box(40.2D, 40.8D, 65.0D, 66.8D, plane - 0.3D, plane + 0.3D)));
    }

    private static double distance(Vector4d plane, Vec3d point) {
        return plane.x * point.x() + plane.y * point.y() + plane.z * point.z() + plane.w;
    }

    private static Vector4f clip(Matrix4f projection, Matrix4f view, Vec3d camera, Vec3d point) {
        Vector4f relative = new Vector4f((float) (point.x() - camera.x()), (float) (point.y() - camera.y()), (float) (point.z() - camera.z()), 1.0F);
        return new Matrix4f(projection).mul(view).transform(relative);
    }

    private static Matrix4f projection() {
        return new Matrix4f().setPerspective((float) Math.toRadians(70.0D), 16.0F / 9.0F, 1024.0F, 0.05F, false);
    }

    private static Matrix4f view(float yaw, float pitch) {
        Quaternionf rotation = new Quaternionf().rotationYXZ((float) Math.PI - yaw * (float) (Math.PI / 180.0D), -pitch * (float) (Math.PI / 180.0D), 0.0F);
        return new Matrix4f().rotation(rotation.conjugate(new Quaternionf()));
    }

    private static List<Similarity> transforms() {
        List<Similarity> transforms = new ArrayList<>();
        for (Face x : Face.values()) {
            for (Face y : Face.values()) {
                for (Face z : Face.values()) {
                    if (x.getAxis() == y.getAxis() || x.getAxis() == z.getAxis() || y.getAxis() == z.getAxis()) {
                        continue;
                    }
                    OpticTransform rigid = OpticTransform.of(AxisPermutation.of(x, y, z), 1_000.5D, -20.0D, -3_000.25D);
                    transforms.add(Similarity.of(rigid, 1.0D));
                    transforms.add(Similarity.of(rigid, 2.5D));
                }
            }
        }
        transforms.add(Similarity.of(portal(Face.S, true).mirrorTransform(), 1.0D));
        transforms.add(Similarity.of(OpticTransform.mirror(portal(Face.E, true).frame(), new Vec3d(3.0D, 64.0D, 0.0D), QuarterTurn.of(1)), 1.0D));
        return transforms;
    }

    private static ApertureDescriptor portal(Face facing, boolean frontSide) {
        boolean[] open = new boolean[9];
        Arrays.fill(open, true);
        return new ApertureDescriptor(3, 65, -6, facing.ordinal(), frontSide, 0, false, 3, 3,
            ApertureDescriptor.apertureMask(3, 3, open), ShapeDescriptor.FULL, 0.0F, 0.0F, 1.0F, 8, 2,
            ApertureDescriptor.BLACKOUT_OFF, 0, ApertureDescriptor.MASK_AIR_PROJECT, 0, 0, ApertureKind.FRAME, 0.0D, 0, 1L, List.of());
    }
}
