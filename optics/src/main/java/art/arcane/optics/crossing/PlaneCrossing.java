package art.arcane.optics.crossing;

import art.arcane.optics.math.Vec3;
import art.arcane.optics.frame.Frame;

public record PlaneCrossing(Frame frame, Vec3 origin, Vec3 point,
                             Vec3 velocity, Vec3 look, boolean frontSide) {
    public static Vec3 intersection(Frame frame, Vec3 origin,
                                              Vec3 start, Vec3 end) {
        double startDistance = distance(frame, origin, start);
        double endDistance = distance(frame, origin, end);
        if (startDistance == endDistance || startDistance > 0.0D && endDistance > 0.0D
            || startDistance < 0.0D && endDistance < 0.0D) {
            return null;
        }
        double fraction = startDistance / (startDistance - endDistance);
        return start.add(end.subtract(start).multiply(fraction));
    }

    public static PlaneCrossing create(Frame frame, Vec3 origin, Motion motion) {
        double startDistance = distance(frame, origin, motion.start());
        boolean frontSide = startDistance == 0.0D
            ? distance(frame, new Vec3(0, 0, 0), motion.velocity()) <= 0.0D
            : startDistance > 0.0D;
        return new PlaneCrossing(frame.view(frontSide), origin, motion.end(), motion.velocity(), motion.look(), frontSide);
    }

    public Vec3 rejectionPoint() {
        Vec3 normal = new Vec3(frame.getNormal().x(), frame.getNormal().y(), frame.getNormal().z());
        double sourceDistance = distance(frame, origin, point);
        return point.add(normal.multiply(1.25D - Math.min(0.0D, sourceDistance)));
    }

    public double sourceSideDistance(Vec3 current) {
        return distance(frame, point, current);
    }

    public Vec3 outPoint(Frame destination, Vec3 destinationOrigin) {
        return frame.transformPoint(point, origin, destinationOrigin, destination.view(frontSide));
    }

    public Vec3 outVelocity(Frame destination) {
        return frame.transformVector(velocity, destination.view(frontSide));
    }

    public Vec3 outLook(Frame destination) {
        return frame.transformVector(look, destination.view(frontSide));
    }

    private static double distance(Frame frame, Vec3 origin, Vec3 point) {
        return (point.x() - origin.x()) * frame.getNormal().x()
            + (point.y() - origin.y()) * frame.getNormal().y()
            + (point.z() - origin.z()) * frame.getNormal().z();
    }

    public record Motion(Vec3 start, Vec3 end, Vec3 velocity, Vec3 look) {
    }
}
