package art.arcane.optics.volume;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Box;

public final class ProjectionVolume {
    private ProjectionVolume() {
    }

    public static double portalPlaneClearance(Box area, Frame frame) {
        double normalDepth;
        if (frame.getNormal().x() != 0) {
            normalDepth = area.sizeX();
        } else if (frame.getNormal().y() != 0) {
            normalDepth = area.sizeY();
        } else {
            normalDepth = area.sizeZ();
        }
        return Math.max(0.5001D, (normalDepth * 0.5D) + 0.001D);
    }

    public static int minBlockForCenter(double centerMin) {
        return (int) Math.ceil(centerMin - 0.500001D);
    }

    public static int maxBlockForCenter(double centerMax) {
        return (int) Math.floor(centerMax - 0.499999D);
    }

    public static boolean projectsBehindPortalPlane(double signedCellDistance, boolean eyeFrontSide, double portalPlaneClearance) {
        if (Math.abs(signedCellDistance) <= portalPlaneClearance) {
            return false;
        }
        boolean cellFrontSide = signedCellDistance >= 0.0D;
        return cellFrontSide != eyeFrontSide;
    }
}
