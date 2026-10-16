package art.arcane.wormholes.portal;

import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;

public final class ArrivalClearance {
    private static final double CLEARANCE = 1.0E-3D;
    private static final double AXIS_TOLERANCE = 1.0E-9D;
    private static final double HALF_CELL = 0.5D;

    private ArrivalClearance() {
    }

    public static double lift(Similarity toward, Vec3d sourceOrigin, Face sourceView, Box body) {
        Vec3d travel = new Vec3d(-sourceView.x(), -sourceView.y(), -sourceView.z());
        Vec3d exit = toward.vector(travel);
        if (Math.abs(exit.x()) > AXIS_TOLERANCE || Math.abs(exit.z()) > AXIS_TOLERANCE || Math.abs(exit.y()) <= AXIS_TOLERANCE) {
            return 0.0D;
        }
        double near = toward.point(sourceOrigin.subtract(travel.multiply(HALF_CELL))).y();
        double far = toward.point(sourceOrigin.add(travel.multiply(HALF_CELL))).y();
        if (exit.y() > 0.0D) {
            return Math.max(0.0D, Math.max(near, far) + CLEARANCE - body.getYa());
        }
        return Math.min(0.0D, Math.min(near, far) - CLEARANCE - body.getYb());
    }
}
