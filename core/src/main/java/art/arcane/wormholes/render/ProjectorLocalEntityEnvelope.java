package art.arcane.wormholes.render;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.portal.PortalFrame;

public final class ProjectorLocalEntityEnvelope {
    private ProjectorLocalEntityEnvelope() {
    }

    public static boolean envelopeFullyProjected(double minX,
                                          double minY,
                                          double minZ,
                                          double maxX,
                                          double maxY,
                                          double maxZ,
                                          GeometryVector origin,
                                          PortalFrame frame,
                                          Frustum4D frustum,
                                          boolean eyeFrontSide,
                                          double clearance,
                                          double maxDepth) {
        double firstSignedDistance = dot(
            minX - origin.getX(), minY - origin.getY(), minZ - origin.getZ(), frame);
        double secondSignedDistance = dot(
            maxX - origin.getX(), maxY - origin.getY(), maxZ - origin.getZ(), frame);
        double minSignedDistance = Math.min(firstSignedDistance, secondSignedDistance);
        double maxSignedDistance = Math.max(firstSignedDistance, secondSignedDistance);
        if (eyeFrontSide) {
            if (maxSignedDistance >= -clearance || minSignedDistance < -maxDepth) {
                return false;
            }
        } else if (minSignedDistance <= clearance || maxSignedDistance > maxDepth) {
            return false;
        }
        return frustum.containsBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public static double dot(double x, double y, double z, PortalFrame frame) {
        return (x * frame.getNormal().x()) + (y * frame.getNormal().y()) + (z * frame.getNormal().z());
    }

}
