package art.arcane.optics.crossing;

import art.arcane.optics.aperture.Aperture;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;

public final class StraddleGeometry {
    private StraddleGeometry() {
    }

    public static Box exclusionSlab(Aperture aperture, Frame frame, Vec3d origin, boolean frontSide, double depth) {
        Box area = aperture.getArea();
        Face normal = frame.view(frontSide).getNormal();
        int axis = normal.axisIndex();
        double plane = origin.component(axis);
        double behind = plane - depth * normal.sign();
        return withAxis(new Box(Math.floor(area.getXa()), Math.floor(area.getXb()) + 1.0D,
            Math.floor(area.getYa()), Math.floor(area.getYb()) + 1.0D,
            Math.floor(area.getZa()), Math.floor(area.getZb()) + 1.0D), axis, Math.min(plane, behind), Math.max(plane, behind));
    }

    public static boolean straddles(Box entityBox, Frame frame, Vec3d origin, boolean frontSide) {
        int axis = frame.view(frontSide).getNormal().axisIndex();
        double plane = origin.component(axis);
        return entityBox.min(axis) < plane && entityBox.max(axis) > plane;
    }

    public static Box clipToFront(Box box, Frame frame, Vec3d origin, boolean frontSide) {
        Face normal = frame.view(frontSide).getNormal();
        int axis = normal.axisIndex();
        double plane = origin.component(axis);
        if (normal.sign() > 0) {
            return withAxis(box, axis, Math.max(box.min(axis), plane), Math.max(box.max(axis), plane));
        }
        return withAxis(box, axis, Math.min(box.min(axis), plane), Math.min(box.max(axis), plane));
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

    private static Box withAxis(Box box, int axis, double low, double high) {
        return switch (axis) {
            case 0 -> new Box(low, high, box.getYa(), box.getYb(), box.getZa(), box.getZb());
            case 1 -> new Box(box.getXa(), box.getXb(), low, high, box.getZa(), box.getZb());
            default -> new Box(box.getXa(), box.getXb(), box.getYa(), box.getYb(), low, high);
        };
    }
}
