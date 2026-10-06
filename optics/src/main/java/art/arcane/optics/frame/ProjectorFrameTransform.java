package art.arcane.optics.frame;

import art.arcane.optics.plate.PlateBox;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;

public final class ProjectorFrameTransform {
    private double fromOriginX;
    private double fromOriginY;
    private double fromOriginZ;
    private double toOriginX;
    private double toOriginY;
    private double toOriginZ;
    private double coordinateSnapTolerance;
    private int sourceX;
    private int sourceY;
    private int sourceZ;
    private int scaleX;
    private int scaleY;
    private int scaleZ;

    public void configure(Frame from, Frame to,
                          double fromOriginX, double fromOriginY, double fromOriginZ,
                          double toOriginX, double toOriginY, double toOriginZ) {
        this.fromOriginX = fromOriginX;
        this.fromOriginY = fromOriginY;
        this.fromOriginZ = fromOriginZ;
        this.toOriginX = toOriginX;
        this.toOriginY = toOriginY;
        this.toOriginZ = toOriginZ;
        coordinateSnapTolerance = coordinateSnapTolerance(
            fromOriginX, fromOriginY, fromOriginZ, toOriginX, toOriginY, toOriginZ);
        mapAxis(from.getRight(), to.getRight());
        mapAxis(from.getUp(), to.getUp());
        mapAxis(from.getNormal(), to.getNormal());
    }

    public void configureMirror(Frame frame, int quarterTurns,
                                double originX, double originY, double originZ) {
        fromOriginX = originX;
        fromOriginY = originY;
        fromOriginZ = originZ;
        toOriginX = originX;
        toOriginY = originY;
        toOriginZ = originZ;
        coordinateSnapTolerance = coordinateSnapTolerance(
            originX, originY, originZ, originX, originY, originZ);
        Face right = frame.getRight();
        Face up = frame.getUp();
        switch (Math.floorMod(quarterTurns, 4)) {
            case 1 -> {
                mapAxis(right, up);
                mapAxis(up, right.reverse());
            }
            case 2 -> {
                mapAxis(right, right.reverse());
                mapAxis(up, up.reverse());
            }
            case 3 -> {
                mapAxis(right, up.reverse());
                mapAxis(up, right);
            }
            default -> {
                mapAxis(right, right);
                mapAxis(up, up);
            }
        }
        mapAxis(frame.getNormal(), frame.getNormal().reverse());
    }

    public void apply(double x, double y, double z, double[] out3) {
        double offsetX = x - fromOriginX;
        double offsetY = y - fromOriginY;
        double offsetZ = z - fromOriginZ;
        out3[0] = snapNearInteger(toOriginX + scaleX * coordinate(sourceX, offsetX, offsetY, offsetZ), coordinateSnapTolerance);
        out3[1] = snapNearInteger(toOriginY + scaleY * coordinate(sourceY, offsetX, offsetY, offsetZ), coordinateSnapTolerance);
        out3[2] = snapNearInteger(toOriginZ + scaleZ * coordinate(sourceZ, offsetX, offsetY, offsetZ), coordinateSnapTolerance);
    }

    public PlateBox transformBox(PlateBox box, int margin) {
        if (box.cells() == 0L) {
            return PlateBox.EMPTY;
        }
        double minX = box.minX() + 0.5D - fromOriginX;
        double minY = box.minY() + 0.5D - fromOriginY;
        double minZ = box.minZ() + 0.5D - fromOriginZ;
        double maxX = (box.minX() + box.sizeX() - 1) + 0.5D - fromOriginX;
        double maxY = (box.minY() + box.sizeY() - 1) + 0.5D - fromOriginY;
        double maxZ = (box.minZ() + box.sizeZ() - 1) + 0.5D - fromOriginZ;
        double firstX = snapNearInteger(toOriginX + scaleX * coordinate(sourceX, minX, minY, minZ), coordinateSnapTolerance);
        double firstY = snapNearInteger(toOriginY + scaleY * coordinate(sourceY, minX, minY, minZ), coordinateSnapTolerance);
        double firstZ = snapNearInteger(toOriginZ + scaleZ * coordinate(sourceZ, minX, minY, minZ), coordinateSnapTolerance);
        double lastX = snapNearInteger(toOriginX + scaleX * coordinate(sourceX, maxX, maxY, maxZ), coordinateSnapTolerance);
        double lastY = snapNearInteger(toOriginY + scaleY * coordinate(sourceY, maxX, maxY, maxZ), coordinateSnapTolerance);
        double lastZ = snapNearInteger(toOriginZ + scaleZ * coordinate(sourceZ, maxX, maxY, maxZ), coordinateSnapTolerance);
        return PlateBox.spanning(
            (int) Math.floor(Math.min(firstX, lastX)) - margin,
            (int) Math.floor(Math.min(firstY, lastY)) - margin,
            (int) Math.floor(Math.min(firstZ, lastZ)) - margin,
            (int) Math.floor(Math.max(firstX, lastX)) + margin,
            (int) Math.floor(Math.max(firstY, lastY)) + margin,
            (int) Math.floor(Math.max(firstZ, lastZ)) + margin);
    }

    private void mapAxis(Face from, Face to) {
        int source = from.getAxis().ordinal();
        int scale = (from.x() + from.y() + from.z()) * (to.x() + to.y() + to.z());
        switch (to.getAxis()) {
            case X -> {
                sourceX = source;
                scaleX = scale;
            }
            case Y -> {
                sourceY = source;
                scaleY = scale;
            }
            case Z -> {
                sourceZ = source;
                scaleZ = scale;
            }
        }
    }

    private static double coordinate(int axis, double x, double y, double z) {
        return axis == 0 ? x : axis == 1 ? y : z;
    }

    public static double coordinateSnapTolerance(double fromX,
                                          double fromY,
                                          double fromZ,
                                          double toX,
                                          double toY,
                                          double toZ) {
        double largestUlp = Math.max(Math.ulp(fromX), Math.ulp(fromY));
        largestUlp = Math.max(largestUlp, Math.ulp(fromZ));
        largestUlp = Math.max(largestUlp, Math.ulp(toX));
        largestUlp = Math.max(largestUlp, Math.ulp(toY));
        largestUlp = Math.max(largestUlp, Math.ulp(toZ));
        return Math.max(1.0E-10D, largestUlp * 8.0D);
    }

    public static double snapNearInteger(double value, double tolerance) {
        double nearestInteger = Math.rint(value);
        return Math.abs(value - nearestInteger) <= tolerance ? nearestInteger : value;
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
