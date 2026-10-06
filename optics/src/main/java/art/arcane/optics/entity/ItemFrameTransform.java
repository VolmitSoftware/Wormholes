package art.arcane.optics.entity;



import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import art.arcane.optics.frame.PortalCoordMap;
import art.arcane.optics.frame.ProjectorFrameTransform;

public final class ItemFrameTransform {
    public static final int NONE = -1;

    private static final int TARGET_MASK = 0x7;
    private static final int QUARTER_TURN_SHIFT = 3;
    private static final int QUARTER_TURN_MASK = 0x3;
    private static final int REVERSED_ROTATION_FLAG = 1 << 5;

    private ItemFrameTransform() {
    }

    public static int between(Face sourceFacing,
                       Frame sourceFrame,
                       Frame targetFrame,
                       double[] scratch3) {
        Face sourceTop = canonicalTop(sourceFacing);
        Face sourceRight = cross(sourceFacing, sourceTop);
        Face targetFacing = sourceFrame.transformDirection(sourceFacing, targetFrame, scratch3);
        Face mappedTop = sourceFrame.transformDirection(sourceTop, targetFrame, scratch3);
        Face mappedRight = sourceFrame.transformDirection(sourceRight, targetFrame, scratch3);
        return encode(targetFacing, mappedTop, mappedRight);
    }

    public static int mirror(Face sourceFacing,
                      Frame frame,
                      int quarterTurns,
                      double[] scratch3) {
        Face sourceTop = canonicalTop(sourceFacing);
        Face sourceRight = cross(sourceFacing, sourceTop);
        Face targetFacing = mirrorDirection(sourceFacing, frame, quarterTurns, scratch3);
        Face mappedTop = mirrorDirection(sourceTop, frame, quarterTurns, scratch3);
        Face mappedRight = mirrorDirection(sourceRight, frame, quarterTurns, scratch3);
        return encode(targetFacing, mappedTop, mappedRight);
    }

    public static int spawnData(int transform) {
        return transform == NONE ? 0 : targetFacing(transform).byteValue();
    }

    public static Face targetFacing(int transform) {
        if (transform == NONE) {
            return null;
        }
        return Face.fromByte((byte) (transform & TARGET_MASK));
    }

    public static int transformRotation(int transform, int sourceRotation, boolean filledMap) {
        if (transform == NONE) {
            return sourceRotation;
        }
        int normalized = Math.floorMod(sourceRotation, 8);
        int sign = (transform & REVERSED_ROTATION_FLAG) == 0 ? 1 : -1;
        int quarterTurns = (transform >> QUARTER_TURN_SHIFT) & QUARTER_TURN_MASK;
        if (filledMap) {
            int transformed = Math.floorMod(quarterTurns + (sign * normalized), 4);
            return (normalized & 4) | transformed;
        }
        return Math.floorMod((quarterTurns * 2) + (sign * normalized), 8);
    }







    public static boolean isReversed(int transform) {
        return transform != NONE && (transform & REVERSED_ROTATION_FLAG) != 0;
    }

    public static <R> R betweenAnchor(double sourceX,
                                  double sourceY,
                                  double sourceZ,
                                  double sourceOriginX,
                                  double sourceOriginY,
                                  double sourceOriginZ,
                                  double targetOriginX,
                                  double targetOriginY,
                                  double targetOriginZ,
                                  Frame sourceFrame,
                                  Frame targetFrame,
                                  double[] scratch3, PositionFactory<R> positions) {
        PortalCoordMap.transformPointInto(blockCenter(sourceX), blockCenter(sourceY), blockCenter(sourceZ),
            sourceOriginX, sourceOriginY, sourceOriginZ,
            targetOriginX, targetOriginY, targetOriginZ,
            sourceFrame, targetFrame, scratch3);
        double targetX = scratch3[0];
        double targetY = scratch3[1];
        double targetZ = scratch3[2];
        sourceFrame.transformVectorInto(1.0D, 1.0D, 1.0D, targetFrame, scratch3);
        double snapTolerance = ProjectorFrameTransform.coordinateSnapTolerance(
            sourceOriginX, sourceOriginY, sourceOriginZ, targetOriginX, targetOriginY, targetOriginZ);
        return anchorPosition(targetX, targetY, targetZ,
            scratch3[0], scratch3[1], scratch3[2], snapTolerance, positions);
    }

    public static <R> R mirrorAnchor(double sourceX,
                                 double sourceY,
                                 double sourceZ,
                                 double originX,
                                 double originY,
                                 double originZ,
                                 Frame frame,
                                 int quarterTurns,
                                 double[] scratch3, PositionFactory<R> positions) {
        PortalCoordMap.mirrorSourceToDisplayPointInto(
            blockCenter(sourceX), blockCenter(sourceY), blockCenter(sourceZ),
            originX, originY, originZ, frame, quarterTurns, scratch3);
        double targetX = scratch3[0];
        double targetY = scratch3[1];
        double targetZ = scratch3[2];
        PortalCoordMap.mirrorSourceToDisplayVectorInto(
            1.0D, 1.0D, 1.0D, frame, quarterTurns, scratch3);
        double snapTolerance = ProjectorFrameTransform.coordinateSnapTolerance(
            originX, originY, originZ, originX, originY, originZ);
        return anchorPosition(targetX, targetY, targetZ,
            scratch3[0], scratch3[1], scratch3[2], snapTolerance, positions);
    }



    /** Every entity the client anchors to a block face rather than to its own centre. */


    public static int encode(Face targetFacing, Face mappedTop, Face mappedRight) {
        int quarterTurns = quarterTurns(targetFacing, mappedTop);
        Face expectedRight = rotatedRight(targetFacing, quarterTurns);
        boolean reversed;
        if (mappedRight == expectedRight) {
            reversed = false;
        } else if (mappedRight == expectedRight.reverse()) {
            reversed = true;
        } else {
            throw new IllegalStateException("Item frame transform produced a non-orthogonal orientation");
        }
        int encoded = targetFacing.byteValue() | (quarterTurns << QUARTER_TURN_SHIFT);
        return reversed ? encoded | REVERSED_ROTATION_FLAG : encoded;
    }

    private static int quarterTurns(Face facing, Face mappedTop) {
        Face top = canonicalTop(facing);
        Face right = cross(facing, top);
        if (mappedTop == top) {
            return 0;
        }
        if (mappedTop == right.reverse()) {
            return 1;
        }
        if (mappedTop == top.reverse()) {
            return 2;
        }
        if (mappedTop == right) {
            return 3;
        }
        throw new IllegalStateException("Item frame transform moved its top outside the target plane");
    }

    private static Face rotatedRight(Face facing, int quarterTurns) {
        Face top = canonicalTop(facing);
        Face right = cross(facing, top);
        return switch (Math.floorMod(quarterTurns, 4)) {
            case 1 -> top;
            case 2 -> right.reverse();
            case 3 -> top.reverse();
            default -> right;
        };
    }

    public static Face canonicalTop(Face facing) {
        return switch (facing) {
            case U -> Face.N;
            case D -> Face.S;
            default -> Face.U;
        };
    }

    public static Face cross(Face left, Face right) {
        return Face.closest(
            (left.y() * right.z()) - (left.z() * right.y()),
            (left.z() * right.x()) - (left.x() * right.z()),
            (left.x() * right.y()) - (left.y() * right.x()));
    }

    private static Face mirrorDirection(Face source,
                                             Frame frame,
                                             int quarterTurns,
                                             double[] scratch3) {
        PortalCoordMap.mirrorSourceToDisplayVectorInto(
            source.x(), source.y(), source.z(), frame, quarterTurns, scratch3);
        return Face.closest(scratch3[0], scratch3[1], scratch3[2]);
    }





    private static double blockCenter(double coordinate) {
        return Math.floor(coordinate) + 0.5D;
    }

    public static <R> R anchorPosition(double x,
                                           double y,
                                           double z,
                                           double xScale,
                                           double yScale,
                                           double zScale,
                                           double snapTolerance, PositionFactory<R> positions) {
        return positions.create(
            anchorCoordinate(x, xScale, snapTolerance),
            anchorCoordinate(y, yScale, snapTolerance),
            anchorCoordinate(z, zScale, snapTolerance));
    }

    private static double anchorCoordinate(double coordinate, double scale, double snapTolerance) {
        double snapped = ProjectorFrameTransform.snapNearInteger(coordinate, snapTolerance);
        return scale > 0.0D ? Math.ceil(snapped) - 1.0D : Math.floor(snapped);
    }


    @FunctionalInterface
    public interface PositionFactory<R> {
        R create(double x, double y, double z);
    }
}
