package art.arcane.wormholes.render;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

final class ProjectorPlaneWindow {
    private static final double EPSILON = 1.0E-7D;

    private final PortalStructure structure;
    private final boolean perCell;
    private final int normalAxis;
    private final int planeCoord;
    private final double originX;
    private final double originY;
    private final double originZ;
    private final double rightX;
    private final double rightY;
    private final double rightZ;
    private final double upX;
    private final double upY;
    private final double upZ;
    private final double eyeSignedDistance;
    private final double rightMin;
    private final double rightMax;
    private final double upMin;
    private final double upMax;
    private final double cellTolerance;

    private ProjectorPlaneWindow(PortalStructure structure,
                                 boolean perCell,
                                 int normalAxis,
                                 int planeCoord,
                                 double originX,
                                 double originY,
                                 double originZ,
                                 double rightX,
                                 double rightY,
                                 double rightZ,
                                 double upX,
                                 double upY,
                                 double upZ,
                                 double eyeSignedDistance,
                                 double rightMin,
                                 double rightMax,
                                 double upMin,
                                 double upMax,
                                 double cellTolerance) {
        this.structure = structure;
        this.perCell = perCell;
        this.normalAxis = normalAxis;
        this.planeCoord = planeCoord;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.rightX = rightX;
        this.rightY = rightY;
        this.rightZ = rightZ;
        this.upX = upX;
        this.upY = upY;
        this.upZ = upZ;
        this.eyeSignedDistance = eyeSignedDistance;
        this.rightMin = rightMin;
        this.rightMax = rightMax;
        this.upMin = upMin;
        this.upMax = upMax;
        this.cellTolerance = cellTolerance;
    }

    static ProjectorPlaneWindow create(PortalStructure structure,
                                       AxisAlignedBB area,
                                       PortalFrame frame,
                                       double originX,
                                       double originY,
                                       double originZ,
                                       double padding,
                                       double eyeSignedDistance) {
        double rightMin = Double.POSITIVE_INFINITY;
        double rightMax = Double.NEGATIVE_INFINITY;
        double upMin = Double.POSITIVE_INFINITY;
        double upMax = Double.NEGATIVE_INFINITY;

        for (int xi = 0; xi < 2; xi++) {
            double x = xi == 0 ? area.getXa() : area.getXb();
            for (int yi = 0; yi < 2; yi++) {
                double y = yi == 0 ? area.getYa() : area.getYb();
                for (int zi = 0; zi < 2; zi++) {
                    double z = zi == 0 ? area.getZa() : area.getZb();
                    double relX = x - originX;
                    double relY = y - originY;
                    double relZ = z - originZ;
                    double right = dot(relX, relY, relZ, frame.getRight());
                    double up = dot(relX, relY, relZ, frame.getUp());
                    rightMin = Math.min(rightMin, right);
                    rightMax = Math.max(rightMax, right);
                    upMin = Math.min(upMin, up);
                    upMax = Math.max(upMax, up);
                }
            }
        }

        Direction normal = frame.getNormal();
        int normalAxis = normal.x() != 0 ? 0 : (normal.y() != 0 ? 1 : 2);
        double normalOrigin = normalAxis == 0 ? originX : (normalAxis == 1 ? originY : originZ);
        int planeCoord = (int) Math.floor(normalOrigin);
        boolean perCell = structure != null && !structure.isFullCuboid();

        return new ProjectorPlaneWindow(structure, perCell, normalAxis, planeCoord,
            originX, originY, originZ,
            frame.getRight().x(), frame.getRight().y(), frame.getRight().z(),
            frame.getUp().x(), frame.getUp().y(), frame.getUp().z(),
            eyeSignedDistance, rightMin - padding, rightMax + padding, upMin - padding, upMax + padding,
            Math.min(padding, 1.0D - EPSILON));
    }

    boolean slabWindow(double eyeX, double eyeY, double eyeZ, double cellSignedDistance, double[] out4) {
        double denom = cellSignedDistance - eyeSignedDistance;
        if (Math.abs(denom) <= EPSILON) {
            return false;
        }
        double t = -eyeSignedDistance / denom;
        if (t < -EPSILON || t > 1.0D + EPSILON) {
            return false;
        }
        if (t <= 1.0E-6D) {
            out4[0] = Double.NEGATIVE_INFINITY;
            out4[1] = Double.POSITIVE_INFINITY;
            out4[2] = Double.NEGATIVE_INFINITY;
            out4[3] = Double.POSITIVE_INFINITY;
            return true;
        }
        double relX = eyeX - originX;
        double relY = eyeY - originY;
        double relZ = eyeZ - originZ;
        double eyeR = (relX * rightX) + (relY * rightY) + (relZ * rightZ);
        double eyeU = (relX * upX) + (relY * upY) + (relZ * upZ);
        out4[0] = eyeR + (((rightMin - EPSILON) - eyeR) / t);
        out4[1] = eyeR + (((rightMax + EPSILON) - eyeR) / t);
        out4[2] = eyeU + (((upMin - EPSILON) - eyeU) / t);
        out4[3] = eyeU + (((upMax + EPSILON) - eyeU) / t);
        return true;
    }

    static int slabBlockMin(double windowLow, double windowHigh, int sign, double axisOrigin, int clampMin) {
        double a = axisOrigin + (sign * windowLow);
        double b = axisOrigin + (sign * windowHigh);
        double lowest = Math.floor(Math.min(a, b)) - 1.0D;
        if (lowest <= clampMin) {
            return clampMin;
        }
        return (int) lowest;
    }

    static int slabBlockMax(double windowLow, double windowHigh, int sign, double axisOrigin, int clampMax) {
        double a = axisOrigin + (sign * windowLow);
        double b = axisOrigin + (sign * windowHigh);
        double highest = Math.ceil(Math.max(a, b)) + 1.0D;
        if (highest >= clampMax) {
            return clampMax;
        }
        return (int) highest;
    }

    boolean containsRayIntersection(double eyeX,
                                    double eyeY,
                                    double eyeZ,
                                    double cellX,
                                    double cellY,
                                    double cellZ,
                                    double cellSignedDistance) {
        double denom = cellSignedDistance - eyeSignedDistance;
        if (Math.abs(denom) <= EPSILON) {
            return false;
        }
        double t = -eyeSignedDistance / denom;
        if (t < -EPSILON || t > 1.0D + EPSILON) {
            return false;
        }
        double hitX = eyeX + ((cellX - eyeX) * t);
        double hitY = eyeY + ((cellY - eyeY) * t);
        double hitZ = eyeZ + ((cellZ - eyeZ) * t);
        double relX = hitX - originX;
        double relY = hitY - originY;
        double relZ = hitZ - originZ;
        double right = (relX * rightX) + (relY * rightY) + (relZ * rightZ);
        double up = (relX * upX) + (relY * upY) + (relZ * upZ);
        if (right < rightMin - EPSILON || right > rightMax + EPSILON
            || up < upMin - EPSILON || up > upMax + EPSILON) {
            return false;
        }
        if (!perCell) {
            return true;
        }
        int bx = (int) Math.floor(hitX);
        int by = (int) Math.floor(hitY);
        int bz = (int) Math.floor(hitZ);
        if (normalAxis == 0) {
            bx = planeCoord;
        } else if (normalAxis == 1) {
            by = planeCoord;
        } else {
            bz = planeCoord;
        }
        if (structure.containsBlock(bx, by, bz)) {
            return true;
        }
        if (cellTolerance <= 0.0D) {
            return false;
        }
        double firstLateral = normalAxis == 0 ? hitY : hitX;
        double secondLateral = normalAxis == 2 ? hitY : hitZ;
        int firstLow = lateralLowOffset(firstLateral, cellTolerance);
        int firstHigh = lateralHighOffset(firstLateral, cellTolerance);
        int secondLow = lateralLowOffset(secondLateral, cellTolerance);
        int secondHigh = lateralHighOffset(secondLateral, cellTolerance);
        for (int first = firstLow; first <= firstHigh; first++) {
            for (int second = secondLow; second <= secondHigh; second++) {
                if (first == 0 && second == 0) {
                    continue;
                }
                int nx = bx;
                int ny = by;
                int nz = bz;
                if (normalAxis == 0) {
                    ny = by + first;
                    nz = bz + second;
                } else if (normalAxis == 1) {
                    nx = bx + first;
                    nz = bz + second;
                } else {
                    nx = bx + first;
                    ny = by + second;
                }
                if (structure.containsBlock(nx, ny, nz)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int lateralLowOffset(double coordinate, double tolerance) {
        return coordinate - Math.floor(coordinate) < tolerance ? -1 : 0;
    }

    private static int lateralHighOffset(double coordinate, double tolerance) {
        return coordinate - Math.floor(coordinate) > 1.0D - tolerance ? 1 : 0;
    }

    private static double dot(double x, double y, double z, Direction direction) {
        return (x * direction.x()) + (y * direction.y()) + (z * direction.z());
    }
}
