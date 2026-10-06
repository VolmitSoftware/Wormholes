package art.arcane.optics.frame;


public final class PortalCoordMap {
    private PortalCoordMap() {
    }

    public static void transformPointInto(double x, double y, double z,
                                          double fromOriginX, double fromOriginY, double fromOriginZ,
                                          double toOriginX, double toOriginY, double toOriginZ,
                                          Frame fromFrame, Frame toFrame,
                                          double[] out3) {
        fromFrame.transformPointInto(x, y, z,
            fromOriginX, fromOriginY, fromOriginZ,
            toOriginX, toOriginY, toOriginZ,
            toFrame, out3);
    }

    public static void mirrorSourceToDisplayPointInto(double x, double y, double z,
                                                       double originX, double originY, double originZ,
                                                       Frame frame, int quarterTurns,
                                                       double[] out3) {
        mirrorSourceToDisplayVectorInto(x - originX, y - originY, z - originZ, frame, quarterTurns, out3);
        out3[0] += originX;
        out3[1] += originY;
        out3[2] += originZ;
    }

    public static void mirrorDisplayToSourcePointInto(double x, double y, double z,
                                                       double originX, double originY, double originZ,
                                                       Frame frame, int quarterTurns,
                                                       double[] out3) {
        mirrorDisplayToSourceVectorInto(x - originX, y - originY, z - originZ, frame, quarterTurns, out3);
        out3[0] += originX;
        out3[1] += originY;
        out3[2] += originZ;
    }

    public static void mirrorSourceToDisplayVectorInto(double x, double y, double z,
                                                        Frame frame, int quarterTurns,
                                                        double[] out3) {
        mirrorVectorInto(x, y, z, frame, quarterTurns, out3);
    }

    public static void mirrorDisplayToSourceVectorInto(double x, double y, double z,
                                                        Frame frame, int quarterTurns,
                                                        double[] out3) {
        mirrorVectorInto(x, y, z, frame, -quarterTurns, out3);
    }

    public static boolean mirrorTransformFlipsWorldUp(Frame planeFrame, int quarterTurns) {
        double right = planeFrame.getRight().y();
        double up = planeFrame.getUp().y();
        double normal = planeFrame.getNormal().y();
        double rotatedRight;
        double rotatedUp;
        switch(Math.floorMod(quarterTurns, 4)) {
            case 1 -> {
                rotatedRight = up;
                rotatedUp = -right;
            }
            case 2 -> {
                rotatedRight = -right;
                rotatedUp = -up;
            }
            case 3 -> {
                rotatedRight = -up;
                rotatedUp = right;
            }
            default -> {
                rotatedRight = right;
                rotatedUp = up;
            }
        }
        return (rotatedRight * planeFrame.getRight().y())
            + (rotatedUp * planeFrame.getUp().y())
            - (normal * planeFrame.getNormal().y()) < -0.5D;
    }

    public static boolean transformFlipsWorldUp(Frame fromFrame, Frame toFrame) {
        double y = ((double) fromFrame.getRight().y() * toFrame.getRight().y())
            + ((double) fromFrame.getUp().y() * toFrame.getUp().y())
            + ((double) fromFrame.getNormal().y() * toFrame.getNormal().y());
        return y < -0.5D;
    }

    private static void mirrorVectorInto(double x, double y, double z,
                                         Frame frame, int quarterTurns,
                                         double[] out3) {
        double right = (x * frame.getRight().x()) + (y * frame.getRight().y()) + (z * frame.getRight().z());
        double up = (x * frame.getUp().x()) + (y * frame.getUp().y()) + (z * frame.getUp().z());
        double normal = (x * frame.getNormal().x()) + (y * frame.getNormal().y()) + (z * frame.getNormal().z());
        double rotatedRight;
        double rotatedUp;
        switch(Math.floorMod(quarterTurns, 4)) {
            case 1 -> {
                rotatedRight = up;
                rotatedUp = -right;
            }
            case 2 -> {
                rotatedRight = -right;
                rotatedUp = -up;
            }
            case 3 -> {
                rotatedRight = -up;
                rotatedUp = right;
            }
            default -> {
                rotatedRight = right;
                rotatedUp = up;
            }
        }
        out3[0] = (rotatedRight * frame.getRight().x()) + (rotatedUp * frame.getUp().x()) - (normal * frame.getNormal().x());
        out3[1] = (rotatedRight * frame.getRight().y()) + (rotatedUp * frame.getUp().y()) - (normal * frame.getNormal().y());
        out3[2] = (rotatedRight * frame.getRight().z()) + (rotatedUp * frame.getUp().z()) - (normal * frame.getNormal().z());
    }
}
