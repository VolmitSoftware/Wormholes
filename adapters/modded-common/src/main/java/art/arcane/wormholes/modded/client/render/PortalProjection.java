package art.arcane.wormholes.modded.client.render;

import org.joml.Matrix4f;
import org.joml.Matrix4d;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Vec3d;

final class PortalProjection {
    private static final Vec3d ORIGIN = new Vec3d(0.0D, 0.0D, 0.0D);
    private static final Vec3d UNIT_X = new Vec3d(1.0D, 0.0D, 0.0D);
    private static final Vec3d UNIT_Y = new Vec3d(0.0D, 1.0D, 0.0D);
    private static final Vec3d UNIT_Z = new Vec3d(0.0D, 0.0D, 1.0D);
    private PortalProjection() {
    }

    static Matrix4f rotation(OpticTransform transform) {
        return new Matrix4f().m00(transform.permutation().x().x()).m01(transform.permutation().x().y()).m02(transform.permutation().x().z())
            .m10(transform.permutation().y().x()).m11(transform.permutation().y().y()).m12(transform.permutation().y().z())
            .m20(transform.permutation().z().x()).m21(transform.permutation().z().y()).m22(transform.permutation().z().z());
    }

    static Matrix4d destinationToSource(OpticTransform transform) {
        return new Matrix4d().m00(transform.permutation().x().x()).m01(transform.permutation().x().y()).m02(transform.permutation().x().z())
            .m10(transform.permutation().y().x()).m11(transform.permutation().y().y()).m12(transform.permutation().y().z())
            .m20(transform.permutation().z().x()).m21(transform.permutation().z().y()).m22(transform.permutation().z().z())
            .m30(transform.translationX()).m31(transform.translationY()).m32(transform.translationZ());
    }

    static Matrix4d matrix(Similarity similarity) {
        Vec3d origin = similarity.point(ORIGIN);
        Vec3d x = similarity.vector(UNIT_X);
        Vec3d y = similarity.vector(UNIT_Y);
        Vec3d z = similarity.vector(UNIT_Z);
        return new Matrix4d().m00(x.x()).m01(x.y()).m02(x.z())
            .m10(y.x()).m11(y.y()).m12(y.z())
            .m20(z.x()).m21(z.y()).m22(z.z())
            .m30(origin.x()).m31(origin.y()).m32(origin.z());
    }

    static Vector4f clipDistance(Matrix4fc projection, Matrix4fc viewRotation, Vector4fc cameraPlane) {
        return new Matrix4f(projection).mul(viewRotation).invert().transpose()
            .transform(new Vector4f(cameraPlane)).negate();
    }

    static Matrix4f clip(Matrix4fc projection, Matrix4fc viewRotation, Vector4fc cameraPlane, boolean zeroToOne) {
        Vector4f plane = new Matrix4f(viewRotation).invert().transpose().transform(new Vector4f(cameraPlane));
        float farClip = zeroToOne ? 0.0f : -1.0f;
        Vector4f corner = new Matrix4f(projection).invert().transform(new Vector4f(
            plane.x > 0 ? -1 : 1, plane.y > 0 ? -1 : 1, farClip, 1));
        float denominator = plane.dot(corner);
        if (!Float.isFinite(denominator) || denominator >= -0.000001f) {
            return new Matrix4f(projection);
        }
        plane.mul((farClip - 1.0f) / denominator);
        return new Matrix4f(projection).m02(projection.m03() + plane.x).m12(projection.m13() + plane.y)
            .m22(projection.m23() + plane.z).m32(projection.m33() + plane.w);
    }
}
