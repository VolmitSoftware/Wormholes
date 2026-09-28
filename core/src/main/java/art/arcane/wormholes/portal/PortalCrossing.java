package art.arcane.wormholes.portal;

import art.arcane.wormholes.geometry.GeometryVector;

public record PortalCrossing(PortalFrame frame, GeometryVector origin, GeometryVector point,
                             GeometryVector velocity, GeometryVector look, boolean frontSide) {
    public static GeometryVector intersection(PortalFrame frame, GeometryVector origin,
                                              GeometryVector start, GeometryVector end) {
        double startDistance = distance(frame, origin, start);
        double endDistance = distance(frame, origin, end);
        if (startDistance == endDistance || startDistance > 0.0D && endDistance > 0.0D
            || startDistance < 0.0D && endDistance < 0.0D) {
            return null;
        }
        double fraction = startDistance / (startDistance - endDistance);
        return start.add(end.subtract(start).multiply(fraction));
    }

    public static PortalCrossing create(PortalFrame frame, GeometryVector origin, Motion motion) {
        double startDistance = distance(frame, origin, motion.start());
        boolean frontSide = startDistance == 0.0D
            ? distance(frame, new GeometryVector(0, 0, 0), motion.velocity()) <= 0.0D
            : startDistance > 0.0D;
        return new PortalCrossing(frame.view(frontSide), origin, motion.end(), motion.velocity(), motion.look(), frontSide);
    }

    public GeometryVector rejectionPoint() {
        GeometryVector normal = new GeometryVector(frame.getNormal().x(), frame.getNormal().y(), frame.getNormal().z());
        double sourceDistance = distance(frame, origin, point);
        return point.add(normal.multiply(1.25D - Math.min(0.0D, sourceDistance)));
    }

    public double sourceSideDistance(GeometryVector current) {
        return distance(frame, point, current);
    }

    public GeometryVector outPoint(PortalFrame destination, GeometryVector destinationOrigin) {
        return frame.transformPoint(point, origin, destinationOrigin, destination.view(frontSide));
    }

    public GeometryVector outVelocity(PortalFrame destination) {
        return frame.transformVector(velocity, destination.view(frontSide));
    }

    public GeometryVector outLook(PortalFrame destination) {
        return frame.transformVector(look, destination.view(frontSide));
    }

    private static double distance(PortalFrame frame, GeometryVector origin, GeometryVector point) {
        return (point.x() - origin.x()) * frame.getNormal().x()
            + (point.y() - origin.y()) * frame.getNormal().y()
            + (point.z() - origin.z()) * frame.getNormal().z();
    }

    public record Motion(GeometryVector start, GeometryVector end, GeometryVector velocity, GeometryVector look) {
    }
}
