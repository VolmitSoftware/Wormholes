package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.aperture.AperturePolygon;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import org.joml.Matrix4d;

import java.util.Objects;

public final class PortalSurface {
    private static final double COINCIDENT_BLOCKS = 1.0D;
    private static final double COINCIDENT_ALIGNMENT = 0.99D;
    private static final Vec3d UNIT_X = new Vec3d(1.0D, 0.0D, 0.0D);
    private static final Vec3d UNIT_Y = new Vec3d(0.0D, 1.0D, 0.0D);
    private static final Vec3d UNIT_Z = new Vec3d(0.0D, 0.0D, 1.0D);

    private final ApertureDescriptor geometry;
    private final AperturePolygon aperture;
    private final Similarity placement;
    private final Similarity local;
    private final boolean reversed;
    private final Vec3d planePoint;
    private final Vec3d servedNormal;
    private final Box area;

    private PortalSurface(ApertureDescriptor geometry, AperturePolygon aperture, Similarity placement, boolean reversed) {
        this.geometry = geometry;
        this.aperture = aperture;
        this.placement = placement;
        this.reversed = reversed;
        local = placement.inverse();
        planePoint = placement.point(localPlanePoint(geometry));
        Face facing = geometry.facingDirection();
        double served = geometry.frontSide() != reversed ? 1.0D : -1.0D;
        servedNormal = placement.direction(new Vec3d(facing.x() * served, facing.y() * served, facing.z() * served));
        area = placement.box(geometry.apertureArea());
    }

    public static PortalSurface of(ApertureDescriptor geometry) {
        Objects.requireNonNull(geometry, "geometry");
        return new PortalSurface(geometry, AperturePolygon.from(geometry), Similarity.IDENTITY, false);
    }

    public PortalSurface through(Similarity toDestination) {
        return new PortalSurface(geometry, aperture, toDestination.compose(placement), !reversed);
    }

    public ApertureDescriptor geometry() {
        return geometry;
    }

    public AperturePolygon aperture() {
        return aperture;
    }

    public Similarity placement() {
        return placement;
    }

    public boolean reversed() {
        return reversed;
    }

    public Vec3d planePoint() {
        return planePoint;
    }

    public Vec3d servedNormal() {
        return servedNormal;
    }

    public Box area() {
        return area;
    }

    public double signedDistance(Vec3d point) {
        return point.subtract(planePoint).dot(servedNormal);
    }

    public boolean servesEye(Vec3d eye) {
        return signedDistance(eye) > 0.0D;
    }

    public boolean contains(Vec3d point) {
        Vec3d mapped = local.point(point);
        return geometry.containsPoint(mapped.x(), mapped.y(), mapped.z());
    }

    public boolean straddles(Box bounds) {
        int normal = geometry.facingDirection().axisIndex();
        Box localBounds = local.box(bounds);
        double plane = geometry.planeCoordinate();
        if (localBounds.min(normal) >= plane || localBounds.max(normal) <= plane) {
            return false;
        }
        Box opening = geometry.apertureArea();
        for (int axis = 0; axis < 3; axis++) {
            if (axis != normal && (localBounds.max(axis) <= opening.min(axis) || localBounds.min(axis) >= opening.max(axis))) {
                return false;
            }
        }
        return true;
    }

    public Matrix4d model() {
        Vec3d origin = placement.point(new Vec3d(geometry.originX(), geometry.originY(), geometry.originZ()));
        Vec3d x = placement.vector(UNIT_X);
        Vec3d y = placement.vector(UNIT_Y);
        Vec3d z = placement.vector(UNIT_Z);
        return new Matrix4d().m00(x.x()).m01(x.y()).m02(x.z())
            .m10(y.x()).m11(y.y()).m12(y.z())
            .m20(z.x()).m21(z.y()).m22(z.z())
            .m30(origin.x()).m31(origin.y()).m32(origin.z());
    }

    public boolean coincides(PortalSurface other) {
        return planePoint.distance(other.planePoint) < COINCIDENT_BLOCKS && servedNormal.dot(other.servedNormal) > COINCIDENT_ALIGNMENT;
    }

    public boolean sameOpening(ApertureDescriptor other) {
        return placement.isRigid() && placement.rigid().isIdentity() && geometry.originX() == other.originX()
            && geometry.originY() == other.originY() && geometry.originZ() == other.originZ() && geometry.facing() == other.facing()
            && geometry.apertureWidth() == other.apertureWidth() && geometry.apertureHeight() == other.apertureHeight();
    }

    private static Vec3d localPlanePoint(ApertureDescriptor geometry) {
        Vec3d center = geometry.apertureArea().center();
        double plane = geometry.planeCoordinate();
        return switch (geometry.facingDirection().getAxis()) {
            case X -> new Vec3d(plane, center.y(), center.z());
            case Y -> new Vec3d(center.x(), plane, center.z());
            case Z -> new Vec3d(center.x(), center.y(), plane);
        };
    }
}
