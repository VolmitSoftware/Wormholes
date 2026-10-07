package art.arcane.optics.entity;

import art.arcane.optics.math.Face;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.AxisPermutation;

public final class ItemFrameTransform {
    public static final int NONE = -1;

    private static final int TARGET_MASK = 0x7;
    private static final int QUARTER_TURN_SHIFT = 3;
    private static final int QUARTER_TURN_MASK = 0x3;
    private static final int REVERSED_ROTATION_FLAG = 1 << 5;

    private ItemFrameTransform() {
    }

    public static int of(Face sourceFacing, OpticTransform transform) {
        Face sourceTop = canonicalTop(sourceFacing);
        Face sourceRight = cross(sourceFacing, sourceTop);
        return encode(transform.face(sourceFacing), transform.face(sourceTop), transform.face(sourceRight));
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

    public static void anchorInto(double sourceX, double sourceY, double sourceZ, OpticTransform transform, double[] out3) {
        transform.pointInto(blockCenter(sourceX), blockCenter(sourceY), blockCenter(sourceZ), out3);
        double tolerance = transform.snapTolerance();
        AxisPermutation permutation = transform.permutation();
        out3[0] = anchorCoordinate(out3[0], positive(permutation.x(), permutation.y(), permutation.z(), 0), tolerance);
        out3[1] = anchorCoordinate(out3[1], positive(permutation.x(), permutation.y(), permutation.z(), 1), tolerance);
        out3[2] = anchorCoordinate(out3[2], positive(permutation.x(), permutation.y(), permutation.z(), 2), tolerance);
    }

    private static int encode(Face targetFacing, Face mappedTop, Face mappedRight) {
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

    private static Face canonicalTop(Face facing) {
        return switch (facing) {
            case U -> Face.N;
            case D -> Face.S;
            default -> Face.U;
        };
    }

    private static Face cross(Face left, Face right) {
        return Face.closest(
            (left.y() * right.z()) - (left.z() * right.y()),
            (left.z() * right.x()) - (left.x() * right.z()),
            (left.x() * right.y()) - (left.y() * right.x()));
    }

    private static double blockCenter(double coordinate) {
        return Math.floor(coordinate) + 0.5D;
    }

    private static double anchorCoordinate(double coordinate, boolean positive, double snapTolerance) {
        double nearest = Math.rint(coordinate);
        double snapped = Math.abs(coordinate - nearest) <= snapTolerance ? nearest : coordinate;
        return positive ? Math.ceil(snapped) - 1.0D : Math.floor(snapped);
    }

    private static boolean positive(Face imageX, Face imageY, Face imageZ, int axis) {
        int sum = imageX.component(axis) + imageY.component(axis) + imageZ.component(axis);
        return sum > 0;
    }
}
