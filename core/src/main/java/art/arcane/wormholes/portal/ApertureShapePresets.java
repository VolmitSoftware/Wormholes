package art.arcane.wormholes.portal;

import java.util.List;

import art.arcane.optics.shape.PlaneTransform;
import art.arcane.optics.shape.Shape;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.optics.shape.Transformed;

public final class ApertureShapePresets {
    private static final double ROTATION_STEP_DEGREES = 45.0D;
    private static final double ROTATION_TOLERANCE = 1.0E-5D;
    private static final List<ShapeDescriptor> PRESETS = List.of(ShapeDescriptor.FULL, ShapeDescriptor.parse("circle"),
        ShapeDescriptor.parse("rounded(radius=0.35)"), ShapeDescriptor.parse("polygon(sides=6)"), ShapeDescriptor.parse("star"),
        ShapeDescriptor.parse("flower"), ShapeDescriptor.parse("heart"), ShapeDescriptor.parse("feather"), ShapeDescriptor.parse("ring"));

    private ApertureShapePresets() {
    }

    public static ShapeDescriptor next(ShapeDescriptor current) {
        return PRESETS.get((PRESETS.indexOf(current) + 1) % PRESETS.size());
    }

    public static ShapeDescriptor rotated(ShapeDescriptor current) {
        if (current.isFull()) {
            return current;
        }
        Shape shape = current.shape();
        if (shape instanceof Transformed transformed && pureRotation(transformed.transform())) {
            double degrees = Math.IEEEremainder(transformed.transform().rotationDegrees() + ROTATION_STEP_DEGREES, 360.0D);
            return ShapeDescriptor.of(Math.abs(degrees) < ROTATION_TOLERANCE ? transformed.shape() : transformed.shape().rotated(degrees),
                current.fit());
        }
        return ShapeDescriptor.of(shape.rotated(ROTATION_STEP_DEGREES), current.fit());
    }

    private static boolean pureRotation(PlaneTransform transform) {
        return !transform.reflects() && transform.isConformal(ROTATION_TOLERANCE) && Math.abs(transform.determinant() - 1.0D) < ROTATION_TOLERANCE
            && Math.abs(transform.tu()) < ROTATION_TOLERANCE && Math.abs(transform.tv()) < ROTATION_TOLERANCE;
    }
}
