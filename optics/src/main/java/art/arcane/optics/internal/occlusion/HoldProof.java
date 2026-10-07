package art.arcane.optics.internal.occlusion;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.volume.ApertureSlab;
public final class HoldProof {
    private static final double EPSILON = 1.0E-7D;
    private static final double MIN_PROOF_PADDING = 0.5D;

    public enum Verdict {
        HOLD,
        BACK_SIDE,
        IN_WINDOW,
        OPEN,
        UNKNOWN;

        public boolean holds() {
            return this == HOLD;
        }
    }

    public enum Occupancy {
        OCCLUDING,
        OPEN,
        UNKNOWN
    }

    private final int normalAxis;
    private final int normalSign;
    private final int planeCoord;
    private final double originX;
    private final double originY;
    private final double originZ;
    private final double originNormal;
    private final double rightX;
    private final double rightY;
    private final double rightZ;
    private final double upX;
    private final double upY;
    private final double upZ;
    private final double rightMin;
    private final double rightMax;
    private final double upMin;
    private final double upMax;
    private final double planeClearance;
    private final double rimTolerance;
    private double eyeX;
    private double eyeY;
    private double eyeZ;
    private double eyeSignedDistance;
    private boolean eyeInFront;

    private HoldProof(int normalAxis,
                               int normalSign,
                               int planeCoord,
                               double originX,
                               double originY,
                               double originZ,
                               Face right,
                               Face up,
                               double rightMin,
                               double rightMax,
                               double upMin,
                               double upMax,
                               double planeClearance,
                               double rimTolerance) {
        this.normalAxis = normalAxis;
        this.normalSign = normalSign;
        this.planeCoord = planeCoord;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.originNormal = normalAxis == 0 ? originX : normalAxis == 1 ? originY : originZ;
        this.rightX = right.x();
        this.rightY = right.y();
        this.rightZ = right.z();
        this.upX = up.x();
        this.upY = up.y();
        this.upZ = up.z();
        this.rightMin = rightMin;
        this.rightMax = rightMax;
        this.upMin = upMin;
        this.upMax = upMax;
        this.planeClearance = planeClearance;
        this.rimTolerance = rimTolerance;
        this.eyeInFront = false;
    }

    public static HoldProof create(Box apertureArea,
                                            Frame projectionFrame,
                                            double originX,
                                            double originY,
                                            double originZ,
                                            double padding) {
        Face right = projectionFrame.getRight();
        Face up = projectionFrame.getUp();
        Face normal = projectionFrame.getNormal();
        double rightMin = Double.POSITIVE_INFINITY;
        double rightMax = Double.NEGATIVE_INFINITY;
        double upMin = Double.POSITIVE_INFINITY;
        double upMax = Double.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            double relX = ((corner & 1) == 0 ? apertureArea.getXa() : apertureArea.getXb()) - originX;
            double relY = ((corner & 2) == 0 ? apertureArea.getYa() : apertureArea.getYb()) - originY;
            double relZ = ((corner & 4) == 0 ? apertureArea.getZa() : apertureArea.getZb()) - originZ;
            double rightValue = (relX * right.x()) + (relY * right.y()) + (relZ * right.z());
            double upValue = (relX * up.x()) + (relY * up.y()) + (relZ * up.z());
            rightMin = Math.min(rightMin, rightValue);
            rightMax = Math.max(rightMax, rightValue);
            upMin = Math.min(upMin, upValue);
            upMax = Math.max(upMax, upValue);
        }
        int normalAxis = normal.axisIndex();
        int normalSign = normal.x() + normal.y() + normal.z();
        double normalOrigin = normalAxis == 0 ? originX : normalAxis == 1 ? originY : originZ;
        double proofPadding = Math.max(MIN_PROOF_PADDING, padding);
        return new HoldProof(normalAxis, normalSign, (int) Math.floor(normalOrigin),
            originX, originY, originZ, right, up,
            rightMin - proofPadding, rightMax + proofPadding, upMin - proofPadding, upMax + proofPadding,
            ApertureSlab.portalPlaneClearance(apertureArea, projectionFrame),
            Math.min(proofPadding, 1.0D - EPSILON));
    }

    public boolean beginEye(double eyeX, double eyeY, double eyeZ) {
        this.eyeX = eyeX;
        this.eyeY = eyeY;
        this.eyeZ = eyeZ;
        double eyeNormal = normalAxis == 0 ? eyeX : normalAxis == 1 ? eyeY : eyeZ;
        eyeSignedDistance = normalSign * (eyeNormal - originNormal);
        eyeInFront = eyeSignedDistance > planeClearance;
        return eyeInFront;
    }

    public Verdict verdict(int cellX, int cellY, int cellZ, LocalOccupancy local) {
        if (!eyeInFront) {
            return Verdict.BACK_SIDE;
        }
        double centerX = cellX + 0.5D;
        double centerY = cellY + 0.5D;
        double centerZ = cellZ + 0.5D;
        double cellNormal = normalAxis == 0 ? centerX : normalAxis == 1 ? centerY : centerZ;
        double cellSignedDistance = normalSign * (cellNormal - originNormal);
        if (cellSignedDistance >= -planeClearance) {
            return Verdict.BACK_SIDE;
        }
        double t = eyeSignedDistance / (eyeSignedDistance - cellSignedDistance);
        double hitX = eyeX + ((centerX - eyeX) * t);
        double hitY = eyeY + ((centerY - eyeY) * t);
        double hitZ = eyeZ + ((centerZ - eyeZ) * t);
        double relX = hitX - originX;
        double relY = hitY - originY;
        double relZ = hitZ - originZ;
        double right = (relX * rightX) + (relY * rightY) + (relZ * rightZ);
        double up = (relX * upX) + (relY * upY) + (relZ * upZ);
        if (right >= rightMin - EPSILON && right <= rightMax + EPSILON
            && up >= upMin - EPSILON && up <= upMax + EPSILON) {
            return Verdict.IN_WINDOW;
        }
        int blockX = normalAxis == 0 ? planeCoord : (int) Math.floor(hitX);
        int blockY = normalAxis == 1 ? planeCoord : (int) Math.floor(hitY);
        int blockZ = normalAxis == 2 ? planeCoord : (int) Math.floor(hitZ);
        Occupancy center = local.occupancy(blockX, blockY, blockZ);
        if (center != Occupancy.OCCLUDING) {
            return revert(center);
        }
        if (rimTolerance <= 0.0D) {
            return Verdict.HOLD;
        }
        double firstLateral = normalAxis == 0 ? hitY : hitX;
        double secondLateral = normalAxis == 2 ? hitY : hitZ;
        int firstLow = lateralLowOffset(firstLateral, rimTolerance);
        int firstHigh = lateralHighOffset(firstLateral, rimTolerance);
        int secondLow = lateralLowOffset(secondLateral, rimTolerance);
        int secondHigh = lateralHighOffset(secondLateral, rimTolerance);
        for (int first = firstLow; first <= firstHigh; first++) {
            for (int second = secondLow; second <= secondHigh; second++) {
                if (first == 0 && second == 0) {
                    continue;
                }
                int neighbourX = blockX;
                int neighbourY = blockY;
                int neighbourZ = blockZ;
                if (normalAxis == 0) {
                    neighbourY = blockY + first;
                    neighbourZ = blockZ + second;
                } else if (normalAxis == 1) {
                    neighbourX = blockX + first;
                    neighbourZ = blockZ + second;
                } else {
                    neighbourX = blockX + first;
                    neighbourY = blockY + second;
                }
                Occupancy neighbour = local.occupancy(neighbourX, neighbourY, neighbourZ);
                if (neighbour != Occupancy.OCCLUDING) {
                    return revert(neighbour);
                }
            }
        }
        return Verdict.HOLD;
    }

    private static Verdict revert(Occupancy occupancy) {
        return occupancy == Occupancy.OPEN ? Verdict.OPEN : Verdict.UNKNOWN;
    }

    private static int lateralLowOffset(double coordinate, double tolerance) {
        return coordinate - Math.floor(coordinate) < tolerance ? -1 : 0;
    }

    private static int lateralHighOffset(double coordinate, double tolerance) {
        return coordinate - Math.floor(coordinate) > 1.0D - tolerance ? 1 : 0;
    }
}
