package art.arcane.optics.crossing;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;

public record PlaneCrossing(Frame frame, Vec3d origin, Vec3d point,
                             Vec3d velocity, Vec3d look, boolean frontSide) {
    public static Vec3d intersection(Frame frame, Vec3d origin,
                                              Vec3d start, Vec3d end) {
        double startDistance = distance(frame, origin, start);
        double endDistance = distance(frame, origin, end);
        if (startDistance == endDistance || startDistance > 0.0D && endDistance > 0.0D
            || startDistance < 0.0D && endDistance < 0.0D) {
            return null;
        }
        double fraction = startDistance / (startDistance - endDistance);
        return start.add(end.subtract(start).multiply(fraction));
    }

    public static PlaneCrossing create(Frame frame, Vec3d origin, Motion motion) {
        double startDistance = distance(frame, origin, motion.start());
        boolean frontSide = startDistance == 0.0D
            ? distance(frame, new Vec3d(0, 0, 0), motion.velocity()) <= 0.0D
            : startDistance > 0.0D;
        return new PlaneCrossing(frame.view(frontSide), origin, motion.end(), motion.velocity(), motion.look(), frontSide);
    }

    public Vec3d rejectionPoint() {
        Vec3d normal = new Vec3d(frame.getNormal().x(), frame.getNormal().y(), frame.getNormal().z());
        double sourceDistance = distance(frame, origin, point);
        return point.add(normal.multiply(1.25D - Math.min(0.0D, sourceDistance)));
    }

    public double sourceSideDistance(Vec3d current) {
        return distance(frame, point, current);
    }

    public OpticTransform toward(Frame destination, Vec3d destinationOrigin) {
        return OpticTransform.between(frame, origin, destination.view(frontSide), destinationOrigin);
    }

    public Vec3d outPoint(Frame destination, Vec3d destinationOrigin) {
        return frame.transformPoint(point, origin, destinationOrigin, destination.view(frontSide));
    }

    public Vec3d outVelocity(Frame destination) {
        return frame.transformVector(velocity, destination.view(frontSide));
    }

    public Vec3d outLook(Frame destination) {
        return frame.transformVector(look, destination.view(frontSide));
    }

    private static double distance(Frame frame, Vec3d origin, Vec3d point) {
        return (point.x() - origin.x()) * frame.getNormal().x()
            + (point.y() - origin.y()) * frame.getNormal().y()
            + (point.z() - origin.z()) * frame.getNormal().z();
    }

    public record Motion(Vec3d start, Vec3d end, Vec3d velocity, Vec3d look) {
    }
}
