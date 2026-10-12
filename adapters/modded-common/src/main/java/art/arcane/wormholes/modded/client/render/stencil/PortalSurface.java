package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.aperture.AperturePolygon;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.PlaneShape;
import art.arcane.optics.shape.PlaneTransform;
import art.arcane.optics.shape.Shape;
import org.joml.Matrix4d;

import java.util.Objects;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class PortalSurface {
    private static final double OPENING_EPSILON = 1.0E-6D;
    private static final Comparator<PlacedRectangle> RECTANGLE_ORDER = Comparator.comparingDouble(PlacedRectangle::minU)
        .thenComparingDouble(PlacedRectangle::minV).thenComparingDouble(PlacedRectangle::maxU).thenComparingDouble(PlacedRectangle::maxV);
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
    private final Box openingArea;
    private volatile Opening opening;

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
        Vec3d min = placement.point(aperture.point(0.0D, 0.0D));
        Vec3d max = placement.point(aperture.point(geometry.apertureWidth(), geometry.apertureHeight()));
        openingArea = new Box(Math.min(min.x(), max.x()), Math.max(min.x(), max.x()), Math.min(min.y(), max.y()), Math.max(min.y(), max.y()),
            Math.min(min.z(), max.z()), Math.max(min.z(), max.z()));
    }

    public static PortalSurface of(ApertureDescriptor geometry) {
        Objects.requireNonNull(geometry, "geometry");
        return new PortalSurface(geometry, AperturePolygon.from(geometry), Similarity.IDENTITY, false);
    }

    public static OpticTransform mirrorTransform(ApertureDescriptor geometry) {
        Objects.requireNonNull(geometry, "geometry");
        return OpticTransform.mirror(geometry.frame(), localPlanePoint(geometry), QuarterTurn.of(geometry.mirrorQuarterTurns()));
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

    public double distance(Vec3d point) {
        double x = Math.clamp(point.x(), area.getXa(), area.getXb());
        double y = Math.clamp(point.y(), area.getYa(), area.getYb());
        double z = Math.clamp(point.z(), area.getZa(), area.getZb());
        double offset = (x - planePoint.x()) * servedNormal.x() + (y - planePoint.y()) * servedNormal.y()
            + (z - planePoint.z()) * servedNormal.z();
        return point.distance(new Vec3d(x - servedNormal.x() * offset, y - servedNormal.y() * offset,
            z - servedNormal.z() * offset));
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

    public boolean sameOpening(PortalSurface other) {
        if (this == other) {
            return true;
        }
        if (other == null || Math.abs(other.planePoint.subtract(planePoint).dot(servedNormal)) > OPENING_EPSILON
            || servedNormal.dot(other.servedNormal) < 1.0D - OPENING_EPSILON) {
            return false;
        }
        int normal = normalAxis();
        for (int axis = 0; axis < 3; axis++) {
            if (axis != normal && (!close(openingArea.min(axis), other.openingArea.min(axis))
                || !close(openingArea.max(axis), other.openingArea.max(axis)))) {
                return false;
            }
        }
        Opening first = opening();
        Opening second = other.opening();
        if (first.rectangles().size() != second.rectangles().size()
            || first.full() != second.full() || !first.full() && (!first.shape().equals(second.shape())
                || !sameTransform(first.shapePlacement(), second.shapePlacement()))) {
            return false;
        }
        for (int index = 0; index < first.rectangles().size(); index++) {
            PlacedRectangle a = first.rectangles().get(index);
            PlacedRectangle b = second.rectangles().get(index);
            if (!close(a.minU(), b.minU()) || !close(a.minV(), b.minV()) || !close(a.maxU(), b.maxU()) || !close(a.maxV(), b.maxV())) {
                return false;
            }
        }
        return true;
    }

    private Opening opening() {
        Opening current = opening;
        if (current != null) {
            return current;
        }
        int normal = normalAxis();
        int u = normal == 0 ? 1 : 0;
        int v = normal == 2 ? 1 : 2;
        Vec3d origin = placement.point(aperture.point(0.0D, 0.0D));
        Vec3d column = placement.vector(aperture.point(1.0D, 0.0D).subtract(aperture.point(0.0D, 0.0D)));
        Vec3d row = placement.vector(aperture.point(0.0D, 1.0D).subtract(aperture.point(0.0D, 0.0D)));
        PlaneTransform plane = new PlaneTransform(coordinate(column, u), coordinate(row, u), coordinate(column, v), coordinate(row, v),
            coordinate(origin, u), coordinate(origin, v));
        List<PlacedRectangle> rectangles = new ArrayList<>(aperture.rectangles().size());
        for (AperturePolygon.Rectangle rectangle : aperture.rectangles()) {
            double aU = plane.a() * rectangle.minColumn() + plane.b() * rectangle.minRow() + plane.tu();
            double aV = plane.c() * rectangle.minColumn() + plane.d() * rectangle.minRow() + plane.tv();
            double bU = plane.a() * rectangle.maxColumn() + plane.b() * rectangle.maxRow() + plane.tu();
            double bV = plane.c() * rectangle.maxColumn() + plane.d() * rectangle.maxRow() + plane.tv();
            rectangles.add(new PlacedRectangle(Math.min(aU, bU), Math.min(aV, bV), Math.max(aU, bU), Math.max(aV, bV)));
        }
        rectangles.sort(RECTANGLE_ORDER);
        PlaneShape shape = aperture.planeShape();
        current = new Opening(shape.isFull(), shape.shape(), plane.compose(shape.placement()), List.copyOf(rectangles));
        opening = current;
        return current;
    }

    private int normalAxis() {
        if (Math.abs(servedNormal.x()) > 0.5D) {
            return 0;
        }
        return Math.abs(servedNormal.y()) > 0.5D ? 1 : 2;
    }

    private static boolean sameTransform(PlaneTransform first, PlaneTransform second) {
        return close(first.a(), second.a()) && close(first.b(), second.b()) && close(first.c(), second.c()) && close(first.d(), second.d())
            && close(first.tu(), second.tu()) && close(first.tv(), second.tv());
    }

    private static boolean close(double first, double second) {
        return Math.abs(first - second) <= OPENING_EPSILON;
    }

    private static double coordinate(Vec3d point, int axis) {
        return switch (axis) {
            case 0 -> point.x();
            case 1 -> point.y();
            default -> point.z();
        };
    }

    private static Vec3d localPlanePoint(ApertureDescriptor geometry) {
        double plane = geometry.planeCoordinate();
        double halfWidth = geometry.apertureWidth() * 0.5D;
        double halfHeight = geometry.apertureHeight() * 0.5D;
        return switch (geometry.facingDirection().getAxis()) {
            case X -> new Vec3d(plane, geometry.originY() + halfHeight, geometry.originZ() + halfWidth);
            case Y -> new Vec3d(geometry.originX() + halfWidth, plane, geometry.originZ() + halfHeight);
            case Z -> new Vec3d(geometry.originX() + halfWidth, geometry.originY() + halfHeight, plane);
        };
    }

    private record Opening(boolean full, Shape shape, PlaneTransform shapePlacement, List<PlacedRectangle> rectangles) {
    }

    private record PlacedRectangle(double minU, double minV, double maxU, double maxV) {
    }
}
