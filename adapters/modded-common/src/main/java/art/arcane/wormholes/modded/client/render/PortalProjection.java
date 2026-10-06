package art.arcane.wormholes.modded.client.render;

import org.joml.Matrix4f;
import art.arcane.optics.stream.ProjectionEnvironment;
import org.joml.Matrix4d;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;

final class PortalProjection {
    private PortalProjection() {
    }

    static Matrix4f rotation(ProjectionEnvironment.Transform transform) {
        return new Matrix4f().m00(transform.xAxis().x()).m01(transform.xAxis().y()).m02(transform.xAxis().z())
            .m10(transform.yAxis().x()).m11(transform.yAxis().y()).m12(transform.yAxis().z())
            .m20(transform.zAxis().x()).m21(transform.zAxis().y()).m22(transform.zAxis().z());
    }

    static Matrix4d destinationToSource(ProjectionEnvironment.Transform transform) {
        return new Matrix4d().m00(transform.xAxis().x()).m01(transform.xAxis().y()).m02(transform.xAxis().z())
            .m10(transform.yAxis().x()).m11(transform.yAxis().y()).m12(transform.yAxis().z())
            .m20(transform.zAxis().x()).m21(transform.zAxis().y()).m22(transform.zAxis().z())
            .m30(transform.translation().x()).m31(transform.translation().y()).m32(transform.translation().z());
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
