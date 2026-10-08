/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: the portal camera transformation of PortalRenderer and the clipping equations of FrontClipping,
 * expressed for Optics similarities and the 26.x view rotation and projection matrices.
 */
package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4d;
import org.joml.Vector4dc;
import org.joml.Vector4f;
import org.joml.Vector4fc;

public final class PortalLayerMath {
    private PortalLayerMath() {
    }

    public static Vec3d innerCamera(Similarity toDestination, Vec3d eye) {
        return toDestination.point(eye);
    }

    public static Matrix4f innerView(Matrix4fc outerView, Similarity toDestination) {
        return new Matrix4f(outerView).mul(rotation(toDestination.rigid().inverse().permutation()));
    }

    public static boolean mirrored(Similarity toDestination) {
        return toDestination.rigid().reflects();
    }

    public static Quaternionf orientation(Matrix4fc view) {
        Matrix4f billboard = new Matrix4f(view);
        if (billboard.determinant3x3() < 0.0F) {
            billboard.m00(-billboard.m00()).m10(-billboard.m10()).m20(-billboard.m20());
        }
        return new Quaternionf().setFromUnnormalized(billboard.invert()).normalize();
    }

    public static Vector4d destinationPlane(PortalSurface surface, Similarity toDestination, double margin) {
        Vec3d served = toDestination.direction(surface.servedNormal());
        Vec3d point = toDestination.point(surface.planePoint());
        return new Vector4d(-served.x(), -served.y(), -served.z(), served.dot(point) - margin);
    }

    public static Vector4f viewPlane(Vector4dc worldPlane, Vec3d camera, Matrix4fc view) {
        double relative = worldPlane.w() + worldPlane.x() * camera.x() + worldPlane.y() * camera.y() + worldPlane.z() * camera.z();
        Vector3f normal = view.transformDirection(new Vector3f((float) worldPlane.x(), (float) worldPlane.y(), (float) worldPlane.z()));
        return new Vector4f(normal.x, normal.y, normal.z, (float) relative);
    }

    public static Vector4f clipPlane(Vector4fc viewPlane, Matrix4fc projection) {
        return new Matrix4f(projection).invert().transpose().transform(new Vector4f(viewPlane));
    }

    public static Matrix4f cullingProjection(Matrix4fc projection, boolean zeroToOne) {
        return new Matrix4f(projection)
            .m02((zeroToOne ? projection.m03() : 0.0F) - projection.m02())
            .m12((zeroToOne ? projection.m13() : 0.0F) - projection.m12())
            .m22((zeroToOne ? projection.m23() : 0.0F) - projection.m22())
            .m32((zeroToOne ? projection.m33() : 0.0F) - projection.m32());
    }

    public static Matrix4f rotation(AxisPermutation permutation) {
        Face x = permutation.x();
        Face y = permutation.y();
        Face z = permutation.z();
        return new Matrix4f().m00(x.x()).m01(x.y()).m02(x.z())
            .m10(y.x()).m11(y.y()).m12(y.z())
            .m20(z.x()).m21(z.y()).m22(z.z());
    }
}
