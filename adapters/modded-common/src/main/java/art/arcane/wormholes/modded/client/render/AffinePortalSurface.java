package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.aperture.AperturePolygon;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.Bounds2;
import art.arcane.optics.shape.FitMode;
import art.arcane.optics.shape.PlaneTransform;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.optics.shape.PlaneShape;
import art.arcane.optics.shape.Shape;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.transform.Affine;
import java.util.List;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4d;
import org.joml.Vector4d;

final class AffinePortalSurface {
    private static final int EDGE = 8;

    private final ViewStreamMessage.PortalAffine sample;
    private final ApertureDescriptor localGeometry;
    private final AperturePolygon localAperture;
    private final PlaneShape localShape;
    private final Matrix4d meshToSource;
    private final AperturePolygon.Plane plane;
    private final AABB bounds;

    AffinePortalSurface(ViewStreamMessage.PortalAffine sample, AffinePortalSurface previous) {
        this.sample = sample;
        Bounds2 local = sample.shape().shape().bounds();
        if (local.isEmpty() || !(local.width() > 0.0D) || !(local.height() > 0.0D)) {
            throw new IllegalArgumentException("Affine portal aperture needs finite non-empty bounds");
        }
        if (previous != null && sample.shape().equals(previous.sample.shape())) {
            localGeometry = previous.localGeometry;
            localAperture = previous.localAperture;
            localShape = previous.localShape;
        } else {
            Shape normalized = sample.shape().shape().transformed(PlaneTransform.mapping(local,
                new Bounds2(-1.0D, -1.0D, 1.0D, 1.0D)));
            localShape = PlaneShape.fit(normalized, FitMode.STRETCH, EDGE, EDGE);
            localGeometry = new ApertureDescriptor(0, 0, 0, Face.S.ordinal(), true, 0, false, EDGE, EDGE,
                new long[] {-1L}, ShapeDescriptor.FULL, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, -0.5D, 0, 0, List.of());
            localAperture = AperturePolygon.from(localGeometry);
        }
        meshToSource = PortalProjection.matrix(sample.apertureToSource()).translate(local.minU(), local.minV(), 0.0D)
            .scale(local.width() / EDGE, local.height() / EDGE, 1.0D);
        plane = plane(sample.apertureToSource());
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 4; corner++) {
            Vec3d point = sample.apertureToSource().point(new Vec3d((corner & 1) == 0 ? local.minU() : local.maxU(),
                (corner & 2) == 0 ? local.minV() : local.maxV(), 0.0D));
            minX = Math.min(minX, point.x());
            minY = Math.min(minY, point.y());
            minZ = Math.min(minZ, point.z());
            maxX = Math.max(maxX, point.x());
            maxY = Math.max(maxY, point.y());
            maxZ = Math.max(maxZ, point.z());
        }
        bounds = new AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(0.01D);
    }

    ViewStreamMessage.PortalAffine sample() {
        return sample;
    }

    ApertureDescriptor localGeometry() {
        return localGeometry;
    }

    AperturePolygon localAperture() {
        return localAperture;
    }

    PlaneShape localShape() {
        return localShape;
    }

    Matrix4d meshToSource() {
        return meshToSource;
    }

    AperturePolygon.Plane plane() {
        return plane;
    }

    AABB bounds() {
        return bounds;
    }

    boolean sameShape(AffinePortalSurface other) {
        return other != null && sample.shape().equals(other.sample.shape());
    }

    static AperturePolygon.Plane plane(Affine apertureToSource) {
        Vector4d normal = PortalProjection.matrix(apertureToSource).invert().transpose().transform(new Vector4d(0, 0, 1, 0));
        double length = Math.sqrt(normal.x * normal.x + normal.y * normal.y + normal.z * normal.z);
        return new AperturePolygon.Plane(normal.x / length, normal.y / length, normal.z / length, normal.w / length);
    }

    static AperturePolygon.Plane transformedPlane(AperturePolygon.Plane source, Matrix4d contentToSource) {
        Vector4d plane = new Matrix4d(contentToSource).transpose().transform(new Vector4d(source.x(), source.y(), source.z(), source.offset()));
        double length = Math.sqrt(plane.x * plane.x + plane.y * plane.y + plane.z * plane.z);
        return new AperturePolygon.Plane(plane.x / length, plane.y / length, plane.z / length, plane.w / length);
    }
}
