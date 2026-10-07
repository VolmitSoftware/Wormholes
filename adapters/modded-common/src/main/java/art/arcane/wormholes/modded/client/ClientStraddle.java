package art.arcane.wormholes.modded.client;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Axis;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Vec3d;

public final class ClientStraddle {
    private static final double VELOCITY_STRETCH = 1.2D;

    private final ApertureDescriptor geometry;
    private final double side;

    private ClientStraddle(ApertureDescriptor geometry) {
        this.geometry = geometry;
        side = geometry.frontSide() ? 1.0D : -1.0D;
    }

    static ClientStraddle of(ApertureDescriptor geometry, Box box, Vec3d previousPosition, Vec3d position, Vec3d velocity, Vec3d eye) {
        double side = geometry.frontSide() ? 1.0D : -1.0D;
        if (geometry.signedDistance(eye.x(), eye.y(), eye.z()) * side <= 0.0D) {
            return null;
        }
        Box stretched = new Box(box);
        Vec3d back = previousPosition.subtract(position);
        stretched.encapsulate(box.getXa() + back.x(), box.getYa() + back.y(), box.getZa() + back.z(),
            box.getXb() + back.x(), box.getYb() + back.y(), box.getZb() + back.z());
        Vec3d ahead = velocity.multiply(VELOCITY_STRETCH);
        stretched.encapsulate(box.getXa() + ahead.x(), box.getYa() + ahead.y(), box.getZa() + ahead.z(),
            box.getXb() + ahead.x(), box.getYb() + ahead.y(), box.getZb() + ahead.z());
        Axis normal = geometry.facingDirection().getAxis();
        double plane = geometry.planeCoordinate();
        if (min(stretched, normal) > plane || max(stretched, normal) < plane) {
            return null;
        }
        Box area = geometry.apertureArea();
        for (Axis axis : Axis.values()) {
            if (axis != normal && (max(stretched, axis) <= min(area, axis) || min(stretched, axis) >= max(area, axis))) {
                return null;
            }
        }
        return new ClientStraddle(geometry);
    }

    public boolean behind(double x, double y, double z) {
        return geometry.signedDistance(x, y, z) * side < 0.0D;
    }

    public ApertureDescriptor geometry() {
        return geometry;
    }

    private static double min(Box box, Axis axis) {
        return switch (axis) {
            case X -> box.getXa();
            case Y -> box.getYa();
            case Z -> box.getZa();
        };
    }

    private static double max(Box box, Axis axis) {
        return switch (axis) {
            case X -> box.getXb();
            case Y -> box.getYb();
            case Z -> box.getZb();
        };
    }
}
