package art.arcane.optics.volume;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;

public final class ProjectionVolume {
    private final int normalAxis;
    private final double facing;
    private final double plane;
    private final boolean frontSide;
    private final double clearance;
    private final double maxDepth;
    private final int[] min;
    private final int[] max;
    private final BlockBox box;

    private ProjectionVolume(Box aperture, Frame frame, double plane, boolean frontSide, double depth, double padding) {
        Face normal = frame.getNormal();
        this.normalAxis = normal.axisIndex();
        this.facing = normal.component(normalAxis);
        this.plane = plane;
        this.frontSide = frontSide;
        this.clearance = portalPlaneClearance(aperture, frame);
        this.maxDepth = depth + clearance;
        double signedMin = frontSide ? -maxDepth : clearance;
        double signedMax = frontSide ? -clearance : maxDepth;
        double centerA = plane + (signedMin / facing);
        double centerB = plane + (signedMax / facing);
        this.min = new int[3];
        this.max = new int[3];
        for (int axis = 0; axis < 3; axis++) {
            if (axis == normalAxis) {
                min[axis] = minBlockForCenter(Math.min(centerA, centerB));
                max[axis] = maxBlockForCenter(Math.max(centerA, centerB));
                continue;
            }
            min[axis] = minBlockForCenter(aperture.min(axis) - padding);
            max[axis] = maxBlockForCenter(aperture.max(axis) + padding);
        }
        this.box = BlockBox.spanning(min[0], min[1], min[2], max[0], max[1], max[2]);
    }

    public static ProjectionVolume of(Box aperture, Frame frame, double plane, boolean frontSide, double depth, double padding) {
        return new ProjectionVolume(aperture, frame, plane, frontSide, depth, padding);
    }

    public static double plane(Frame frame, double x, double y, double z) {
        Face normal = frame.getNormal();
        return normal.x() != 0 ? x : normal.y() != 0 ? y : z;
    }

    public static boolean side(Frame frame, double originX, double originY, double originZ, double x, double y, double z) {
        Face normal = frame.getNormal();
        return ((x - originX) * normal.x()) + ((y - originY) * normal.y()) + ((z - originZ) * normal.z()) >= 0.0D;
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

    public int normalAxis() {
        return normalAxis;
    }

    public int normalMin() {
        return min[normalAxis];
    }

    public int normalMax() {
        return max[normalAxis];
    }

    public int min(int axis) {
        return min[axis];
    }

    public int max(int axis) {
        return max[axis];
    }

    public int farStep() {
        boolean towardPositive = frontSide ? facing < 0.0D : facing > 0.0D;
        return towardPositive ? 1 : -1;
    }

    public double plane() {
        return plane;
    }

    public boolean frontSide() {
        return frontSide;
    }

    public double clearance() {
        return clearance;
    }

    public double maxDepth() {
        return maxDepth;
    }

    public BlockBox box() {
        return box;
    }

    public double distance(double normalCoordinate) {
        return facing * (normalCoordinate - plane);
    }

    public double cellDistance(int normalCoordinate) {
        return facing * ((normalCoordinate + 0.5D) - plane);
    }

    public double signedDistance(double x, double y, double z) {
        return distance(normalAxis == 0 ? x : normalAxis == 1 ? y : z);
    }

    public boolean withinDepth(int normalCoordinate) {
        return Math.abs(cellDistance(normalCoordinate)) <= maxDepth;
    }

    public boolean containsSlab(int normalCoordinate) {
        double distance = cellDistance(normalCoordinate);
        return projectsBehindPortalPlane(distance, frontSide, clearance) && Math.abs(distance) <= maxDepth;
    }

    public boolean contains(int x, int y, int z) {
        return box.contains(x, y, z) && containsSlab(normalAxis == 0 ? x : normalAxis == 1 ? y : z);
    }

    public int depthIndex(int normalCoordinate) {
        return (int) Math.max(0.0D, Math.floor(Math.abs(cellDistance(normalCoordinate)) - clearance));
    }

    public boolean encloses(double firstDistance, double secondDistance) {
        double near = Math.min(firstDistance, secondDistance);
        double far = Math.max(firstDistance, secondDistance);
        if (frontSide) {
            return far < -clearance && near >= -maxDepth;
        }
        return near > clearance && far <= maxDepth;
    }

    public boolean reaches(double firstDistance, double secondDistance) {
        double near = frontSide ? -maxDepth : clearance;
        double far = frontSide ? -clearance : maxDepth;
        return Math.max(firstDistance, secondDistance) >= near && Math.min(firstDistance, secondDistance) <= far;
    }
}
