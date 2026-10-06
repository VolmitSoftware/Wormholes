package art.arcane.optics.volume;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.aperture.CellAperture;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.recursion.RecursiveEndpoints;

public final class PlaneWindow {
    private static final double EPSILON = 1.0E-7D;

    private final CellAperture structure;
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
    private final RowWindow row = new RowWindow();

    private PlaneWindow(CellAperture structure,
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

    public static PlaneWindow create(CellAperture structure,
                                       Box area,
                                       Frame frame,
                                       double originX,
                                       double originY,
                                       double originZ,
                                       double padding,
                                       double eyeSignedDistance) {
        double rightMin = axisBound(area, frame.getRight(), originX, originY, originZ, false);
        double rightMax = axisBound(area, frame.getRight(), originX, originY, originZ, true);
        double upMin = axisBound(area, frame.getUp(), originX, originY, originZ, false);
        double upMax = axisBound(area, frame.getUp(), originX, originY, originZ, true);

        Face normal = frame.getNormal();
        int normalAxis = normal.x() != 0 ? 0 : (normal.y() != 0 ? 1 : 2);
        double normalOrigin = normalAxis == 0 ? originX : (normalAxis == 1 ? originY : originZ);
        int planeCoord = (int) Math.floor(normalOrigin);
        boolean perCell = structure != null && !structure.isFullCuboid();

        return new PlaneWindow(structure, perCell, normalAxis, planeCoord,
            originX, originY, originZ,
            frame.getRight().x(), frame.getRight().y(), frame.getRight().z(),
            frame.getUp().x(), frame.getUp().y(), frame.getUp().z(),
            eyeSignedDistance, rightMin - padding, rightMax + padding, upMin - padding, upMax + padding,
            Math.min(padding, 1.0D - EPSILON));
    }

    public boolean slabWindow(double eyeX, double eyeY, double eyeZ, double cellSignedDistance, double[] out4) {
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

    public static int slabBlockMin(double windowLow, double windowHigh, int sign, double axisOrigin, int clampMin) {
        double a = axisOrigin + (sign * windowLow);
        double b = axisOrigin + (sign * windowHigh);
        double lowest = Math.ceil(Math.min(a, b) - 0.5D);
        if (lowest <= clampMin) {
            return clampMin;
        }
        return (int) lowest;
    }

    public static int slabBlockMax(double windowLow, double windowHigh, int sign, double axisOrigin, int clampMax) {
        double a = axisOrigin + (sign * windowLow);
        double b = axisOrigin + (sign * windowHigh);
        double highest = Math.floor(Math.max(a, b) - 0.5D);
        if (highest >= clampMax) {
            return clampMax;
        }
        return (int) highest;
    }

    public boolean containsRayIntersection(double eyeX,
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

    public boolean clipRay(double eyeX,
                           double eyeY,
                           double eyeZ,
                           double baseX,
                           double baseY,
                           double baseZ,
                           double directionX,
                           double directionY,
                           double directionZ,
                           double signedBase,
                           double signedSlope,
                           double margin,
                           double[] range) {
        double eyeRelX = eyeX - originX;
        double eyeRelY = eyeY - originY;
        double eyeRelZ = eyeZ - originZ;
        double baseRelX = baseX - originX;
        double baseRelY = baseY - originY;
        double baseRelZ = baseZ - originZ;
        double reachBase = eyeSignedDistance - signedBase;
        double reachSlope = -signedSlope;
        return clipWindowAxis((eyeRelX * rightX) + (eyeRelY * rightY) + (eyeRelZ * rightZ),
                (baseRelX * rightX) + (baseRelY * rightY) + (baseRelZ * rightZ),
                (directionX * rightX) + (directionY * rightY) + (directionZ * rightZ),
                rightMin - margin, rightMax + margin, reachBase, reachSlope, range)
            && clipWindowAxis((eyeRelX * upX) + (eyeRelY * upY) + (eyeRelZ * upZ),
                (baseRelX * upX) + (baseRelY * upY) + (baseRelZ * upZ),
                (directionX * upX) + (directionY * upY) + (directionZ * upZ),
                upMin - margin, upMax + margin, reachBase, reachSlope, range);
    }

    public boolean intersectsBlockSilhouette(double eyeX,
                                             double eyeY,
                                             double eyeZ,
                                             double cellX,
                                             double cellY,
                                             double cellZ,
                                             double cellSignedDistance) {
        if (Math.abs(eyeSignedDistance) <= EPSILON) {
            return true;
        }
        double firstDenom = cellSignedDistance - 0.5D - eyeSignedDistance;
        double secondDenom = cellSignedDistance + 0.5D - eyeSignedDistance;
        if (Math.abs(firstDenom) <= EPSILON || Math.abs(secondDenom) <= EPSILON
            || Math.signum(firstDenom) != Math.signum(secondDenom)) {
            return true;
        }
        double firstT = -eyeSignedDistance / firstDenom;
        double secondT = -eyeSignedDistance / secondDenom;
        if (firstT <= 0.0D || secondT <= 0.0D || firstT > 1.0D || secondT > 1.0D) {
            return true;
        }
        double eyeRight = ((eyeX - originX) * rightX) + ((eyeY - originY) * rightY)
            + ((eyeZ - originZ) * rightZ);
        double eyeUp = ((eyeX - originX) * upX) + ((eyeY - originY) * upY)
            + ((eyeZ - originZ) * upZ);
        double deltaRight = ((cellX - eyeX) * rightX) + ((cellY - eyeY) * rightY)
            + ((cellZ - eyeZ) * rightZ);
        double deltaUp = ((cellX - eyeX) * upX) + ((cellY - eyeY) * upY)
            + ((cellZ - eyeZ) * upZ);
        double projectedRightMin = eyeRight + Math.min((deltaRight - 0.5D) * firstT,
            (deltaRight - 0.5D) * secondT);
        double projectedRightMax = eyeRight + Math.max((deltaRight + 0.5D) * firstT,
            (deltaRight + 0.5D) * secondT);
        double projectedUpMin = eyeUp + Math.min((deltaUp - 0.5D) * firstT,
            (deltaUp - 0.5D) * secondT);
        double projectedUpMax = eyeUp + Math.max((deltaUp + 0.5D) * firstT,
            (deltaUp + 0.5D) * secondT);
        return projectedRightMax >= rightMin - EPSILON && projectedRightMin <= rightMax + EPSILON
            && projectedUpMax >= upMin - EPSILON && projectedUpMin <= upMax + EPSILON;
    }

    public boolean containsRow(int axis, double eyeX, double eyeY, double eyeZ,
                        double x, double y, double z, double end, double cellSignedDistance) {
        return !perCell && normalAxis != axis
            && containsRayIntersection(eyeX, eyeY, eyeZ, x, y, z, cellSignedDistance)
            && containsRayIntersection(eyeX, eyeY, eyeZ,
                axis == 0 ? end : x, axis == 1 ? end : y, axis == 2 ? end : z, cellSignedDistance);
    }

    public void prepareRow(int axis, double eyeX, double eyeY, double eyeZ,
                    double x, double y, double z, double cellSignedDistance) {
        row.axis = axis;
        row.valid = false;
        row.cached = false;
        double denom = cellSignedDistance - eyeSignedDistance;
        if (axis == normalAxis || Math.abs(denom) <= EPSILON) {
            return;
        }
        double t = -eyeSignedDistance / denom;
        if (t < -EPSILON || t > 1.0D + EPSILON) {
            return;
        }
        double hitX = eyeX + ((x - eyeX) * t);
        double hitY = eyeY + ((y - eyeY) * t);
        double hitZ = eyeZ + ((z - eyeZ) * t);
        double rightComponent = axis == 0 ? rightX : axis == 1 ? rightY : rightZ;
        boolean variesRight = rightComponent != 0.0D;
        double fixed = variesRight
            ? ((hitX - originX) * upX) + ((hitY - originY) * upY) + ((hitZ - originZ) * upZ)
            : ((hitX - originX) * rightX) + ((hitY - originY) * rightY) + ((hitZ - originZ) * rightZ);
        double fixedMin = variesRight ? upMin : rightMin;
        double fixedMax = variesRight ? upMax : rightMax;
        if (fixed < fixedMin - EPSILON || fixed > fixedMax + EPSILON) {
            return;
        }
        row.valid = true;
        row.t = t;
        row.eye = axis == 0 ? eyeX : axis == 1 ? eyeY : eyeZ;
        row.origin = axis == 0 ? originX : axis == 1 ? originY : originZ;
        row.sign = variesRight ? rightComponent : axis == 0 ? upX : axis == 1 ? upY : upZ;
        row.min = (variesRight ? rightMin : upMin) - EPSILON;
        row.max = (variesRight ? rightMax : upMax) + EPSILON;
        row.x = normalAxis == 0 ? planeCoord : (int) Math.floor(hitX);
        row.y = normalAxis == 1 ? planeCoord : (int) Math.floor(hitY);
        row.z = normalAxis == 2 ? planeCoord : (int) Math.floor(hitZ);
        row.fixedAxis = 3 - normalAxis - axis;
        double fixedHit = row.fixedAxis == 0 ? hitX : row.fixedAxis == 1 ? hitY : hitZ;
        row.fixedLow = cellTolerance <= 0.0D ? 0 : lateralLowOffset(fixedHit, cellTolerance);
        row.fixedHigh = cellTolerance <= 0.0D ? 0 : lateralHighOffset(fixedHit, cellTolerance);
    }

    public boolean containsRowCell(int coordinate) {
        if (!row.valid) {
            return false;
        }
        double hit = row.eye + ((coordinate + 0.5D - row.eye) * row.t);
        double lateral = (hit - row.origin) * row.sign;
        if (lateral < row.min || lateral > row.max) {
            return false;
        }
        if (!perCell) {
            return true;
        }
        int cell = (int) Math.floor(hit);
        int low = cell + (cellTolerance <= 0.0D ? 0 : lateralLowOffset(hit, cellTolerance));
        int high = cell + (cellTolerance <= 0.0D ? 0 : lateralHighOffset(hit, cellTolerance));
        if (row.cached && row.cachedLow == low && row.cachedHigh == high) {
            return row.cachedResult;
        }
        row.cached = true;
        row.cachedLow = low;
        row.cachedHigh = high;
        row.cachedResult = containsRowMembers(low, high);
        return row.cachedResult;
    }

    private boolean containsRowMembers(int low, int high) {
        for (int coordinate = low; coordinate <= high; coordinate++) {
            for (int offset = row.fixedLow; offset <= row.fixedHigh; offset++) {
                int x = row.axis == 0 ? coordinate : row.x + (row.fixedAxis == 0 ? offset : 0);
                int y = row.axis == 1 ? coordinate : row.y + (row.fixedAxis == 1 ? offset : 0);
                int z = row.axis == 2 ? coordinate : row.z + (row.fixedAxis == 2 ? offset : 0);
                if (structure.containsBlock(x, y, z)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean clipWindowAxis(double eyeCoordinate,
                                   double pointBase,
                                   double pointSlope,
                                   double low,
                                   double high,
                                   double reachBase,
                                   double reachSlope,
                                   double[] range) {
        double lowOffset = low - eyeCoordinate;
        double highOffset = high - eyeCoordinate;
        double pointOffset = pointBase - eyeCoordinate;
        return RecursiveEndpoints.clipLinear((eyeSignedDistance * pointOffset) - (lowOffset * reachBase),
                (eyeSignedDistance * pointSlope) - (lowOffset * reachSlope), range)
            && RecursiveEndpoints.clipLinear((highOffset * reachBase) - (eyeSignedDistance * pointOffset),
                (highOffset * reachSlope) - (eyeSignedDistance * pointSlope), range);
    }

    private static double axisBound(Box area, Face direction,
                                    double originX, double originY, double originZ, boolean maximum) {
        int sign = direction.x() + direction.y() + direction.z();
        boolean upper = maximum == (sign > 0);
        return switch (direction.getAxis()) {
            case X -> ((upper ? area.getXb() : area.getXa()) - originX) * sign;
            case Y -> ((upper ? area.getYb() : area.getYa()) - originY) * sign;
            case Z -> ((upper ? area.getZb() : area.getZa()) - originZ) * sign;
        };
    }

    private static int lateralLowOffset(double coordinate, double tolerance) {
        return coordinate - Math.floor(coordinate) < tolerance ? -1 : 0;
    }

    private static int lateralHighOffset(double coordinate, double tolerance) {
        return coordinate - Math.floor(coordinate) > 1.0D - tolerance ? 1 : 0;
    }

    private static final class RowWindow {
        private int axis;
        private int fixedAxis;
        private int x;
        private int y;
        private int z;
        private int fixedLow;
        private int fixedHigh;
        private int cachedLow;
        private int cachedHigh;
        private double t;
        private double eye;
        private double origin;
        private double sign;
        private double min;
        private double max;
        private boolean valid;
        private boolean cached;
        private boolean cachedResult;
    }
}
