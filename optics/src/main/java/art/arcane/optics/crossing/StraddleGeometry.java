package art.arcane.optics.crossing;

import art.arcane.optics.aperture.Aperture;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Axis;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;

public final class StraddleGeometry {
    private StraddleGeometry() {
    }

    public static Box exclusionSlab(Aperture aperture, Frame frame, Vec3d origin, boolean frontSide, double depth) {
        Box area = aperture.getArea();
        Face normal = frame.view(frontSide).getNormal();
        double plane = coordinate(origin, normal.getAxis());
        double behind = plane - depth * sign(normal);
        return withAxis(new Box(Math.floor(area.getXa()), Math.floor(area.getXb()) + 1.0D,
            Math.floor(area.getYa()), Math.floor(area.getYb()) + 1.0D,
            Math.floor(area.getZa()), Math.floor(area.getZb()) + 1.0D), normal.getAxis(), Math.min(plane, behind), Math.max(plane, behind));
    }

    public static boolean straddles(Box entityBox, Frame frame, Vec3d origin, boolean frontSide) {
        Axis axis = frame.view(frontSide).getNormal().getAxis();
        double plane = coordinate(origin, axis);
        return min(entityBox, axis) < plane && max(entityBox, axis) > plane;
    }

    public static Box clipToFront(Box box, Frame frame, Vec3d origin, boolean frontSide) {
        Face normal = frame.view(frontSide).getNormal();
        Axis axis = normal.getAxis();
        double plane = coordinate(origin, axis);
        if (sign(normal) > 0) {
            return withAxis(box, axis, Math.max(min(box, axis), plane), Math.max(max(box, axis), plane));
        }
        return withAxis(box, axis, Math.min(min(box, axis), plane), Math.min(max(box, axis), plane));
    }

    public static Box mappedBox(Box box, OpticTransform toward) {
        return toward.box(box);
    }

    public static Vec3d mappedMove(Vec3d move, OpticTransform toward) {
        return toward.vector(move);
    }

    public static Vec3d unmappedMove(Vec3d move, OpticTransform toward) {
        double[] out = new double[3];
        toward.permutation().inverse().vectorInto(move.x(), move.y(), move.z(), out);
        return new Vec3d(out[0], out[1], out[2]);
    }

    private static Box withAxis(Box box, Axis axis, double low, double high) {
        return switch (axis) {
            case X -> new Box(low, high, box.getYa(), box.getYb(), box.getZa(), box.getZb());
            case Y -> new Box(box.getXa(), box.getXb(), low, high, box.getZa(), box.getZb());
            case Z -> new Box(box.getXa(), box.getXb(), box.getYa(), box.getYb(), low, high);
        };
    }

    private static double coordinate(Vec3d point, Axis axis) {
        return switch (axis) {
            case X -> point.x();
            case Y -> point.y();
            case Z -> point.z();
        };
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

    private static int sign(Face face) {
        return face.x() + face.y() + face.z();
    }
}
