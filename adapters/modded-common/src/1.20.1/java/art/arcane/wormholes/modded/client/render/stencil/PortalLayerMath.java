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
import art.arcane.wormholes.modded.client.ClientRenderAccess;
import art.arcane.optics.aperture.AperturePolygon;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
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
        return new Matrix4f(outerView).mul(rotation(toDestination.rigid().inverse().permutation()))
            .scale((float) (1.0D / toDestination.scale()));
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
        return new Matrix4f(view).invert().transpose().transform(new Vector4f((float) worldPlane.x(),
            (float) worldPlane.y(), (float) worldPlane.z(), (float) relative));
    }

    public static Vector4f clipPlane(Vector4fc viewPlane, Matrix4fc projection) {
        return new Matrix4f(projection).invert().transpose().transform(new Vector4f(viewPlane));
    }

    public static Matrix4f cullingProjection(Matrix4fc projection, boolean zeroToOne) {
        if (!ClientRenderAccess.reversedDepth()) {
            return new Matrix4f(projection);
        }
        return new Matrix4f(projection)
            .m02((zeroToOne ? projection.m03() : 0.0F) - projection.m02())
            .m12((zeroToOne ? projection.m13() : 0.0F) - projection.m12())
            .m22((zeroToOne ? projection.m23() : 0.0F) - projection.m22())
            .m32((zeroToOne ? projection.m33() : 0.0F) - projection.m32());
    }

    public static AperturePolygon.ClipDepth coverageDepth(double[] matrix, boolean zeroToOne) {
        if (ClientRenderAccess.reversedDepth()) {
            return zeroToOne ? AperturePolygon.ClipDepth.ZERO_TO_ONE : AperturePolygon.ClipDepth.NEGATIVE_ONE_TO_ONE;
        }
        matrix[2] = matrix[3];
        matrix[6] = matrix[7];
        matrix[10] = matrix[11];
        matrix[14] = matrix[15];
        return AperturePolygon.ClipDepth.ZERO_TO_ONE;
    }

    /**
     * Lengyel's oblique near plane: replaces the near plane of a standard OpenGL perspective projection with the
     * view-space plane, the fallback when vertex shaders cannot write clip distances. The camera must be on the
     * plane's negative side, which holds for a layer camera behind its destination portal.
     */
    public static Matrix4f oblique(Matrix4fc projection, Vector4fc viewPlane) {
        Matrix4f result = new Matrix4f(projection);
        if (viewPlane.w() >= 0.0F) {
            return result;
        }
        float qx = (Math.signum(viewPlane.x()) + projection.m20()) / projection.m00();
        float qy = (Math.signum(viewPlane.y()) + projection.m21()) / projection.m11();
        float qz = -1.0F;
        float qw = (1.0F + projection.m22()) / projection.m32();
        float dot = viewPlane.x() * qx + viewPlane.y() * qy + viewPlane.z() * qz + viewPlane.w() * qw;
        if (Math.abs(dot) < 1.0E-12F) {
            return result;
        }
        float scale = 2.0F / dot;
        return result.m02(viewPlane.x() * scale - projection.m03())
            .m12(viewPlane.y() * scale - projection.m13())
            .m22(viewPlane.z() * scale - projection.m23())
            .m32(viewPlane.w() * scale - projection.m33());
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
