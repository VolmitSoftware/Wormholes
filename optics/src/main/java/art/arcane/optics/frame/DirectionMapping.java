package art.arcane.optics.frame;

import art.arcane.optics.math.Face;

public final class DirectionMapping {
    private static final int HANDEDNESS_UNCOMPUTED = Integer.MIN_VALUE;

    private final Frame fromFrame;
    private final Frame toFrame;
    private final Frame mirrorFrame;
    private final int quarterTurns;
    private final double[] scratch3;
    private final Face[] axes;
    private int imageQuarterTurns;
    private boolean reflects;

    private DirectionMapping(Frame fromFrame, Frame toFrame, Frame mirrorFrame, int quarterTurns, double[] scratch3) {
        this.axes = null;
        this.fromFrame = fromFrame;
        this.toFrame = toFrame;
        this.mirrorFrame = mirrorFrame;
        this.quarterTurns = quarterTurns;
        this.scratch3 = scratch3;
        this.imageQuarterTurns = HANDEDNESS_UNCOMPUTED;
        this.reflects = false;
    }

    private DirectionMapping(Face[] axes) {
        this.axes = axes;
        this.fromFrame = null;
        this.toFrame = null;
        this.mirrorFrame = null;
        this.quarterTurns = 0;
        this.scratch3 = null;
        this.imageQuarterTurns = HANDEDNESS_UNCOMPUTED;
    }

    public static DirectionMapping axes(Face x, Face y, Face z) {
        if (x.getAxis() == y.getAxis() || x.getAxis() == z.getAxis() || y.getAxis() == z.getAxis()) {
            throw new IllegalArgumentException("Mapped axes must be perpendicular");
        }
        return new DirectionMapping(new Face[] {x, y, z});
    }

    /** Quarter turns clockwise from above that this mapping applies to the horizontal plane. */
    public int quarterTurnsClockwise() {
        if (imageQuarterTurns == HANDEDNESS_UNCOMPUTED) {
            computeHandedness();
        }
        return imageQuarterTurns;
    }

    /** True when the mapping mirrors the horizontal plane, so handed block states must swap sides. */
    public boolean reflects() {
        if (imageQuarterTurns == HANDEDNESS_UNCOMPUTED) {
            computeHandedness();
        }
        return reflects;
    }

    /** Maps a 16-step rotation index through the mapping, reflecting first and then turning. */
    public int mapRotation(int index) {
        int reflected = reflects() ? Rotation16.reflect(index, Face.E) : index;
        return Rotation16.rotate(reflected, quarterTurnsClockwise());
    }

    private void computeHandedness() {
        int southIndex = rotationIndex(map(Face.S));
        int eastIndex = rotationIndex(map(Face.E));
        if (southIndex < 0 || eastIndex < 0) {
            imageQuarterTurns = 0;
            reflects = false;
            return;
        }
        imageQuarterTurns = southIndex / 4;
        reflects = eastIndex != Rotation16.rotate(12, imageQuarterTurns);
    }

    public static DirectionMapping between(Frame fromFrame, Frame toFrame, double[] scratch3) {
        return new DirectionMapping(fromFrame, toFrame, null, 0, scratch3);
    }

    public static DirectionMapping mirror(Frame frame, int quarterTurns, double[] scratch3) {
        return new DirectionMapping(null, null, frame, quarterTurns, scratch3);
    }

    public Face map(Face source) {
        if (axes != null) {
            return Face.closest(source.x() * axes[0].x() + source.y() * axes[1].x() + source.z() * axes[2].x(),
                source.x() * axes[0].y() + source.y() * axes[1].y() + source.z() * axes[2].y(),
                source.x() * axes[0].z() + source.y() * axes[1].z() + source.z() * axes[2].z());
        }
        if(mirrorFrame != null) {
            PortalCoordMap.mirrorSourceToDisplayVectorInto(source.x(), source.y(), source.z(), mirrorFrame, quarterTurns, scratch3);
            return Face.closest(scratch3[0], scratch3[1], scratch3[2]);
        }
        return fromFrame.transformDirection(source, toFrame, scratch3);
    }
    private static int rotationIndex(Face direction) {
        return switch (direction) {
            case S -> 0;
            case W -> 4;
            case N -> 8;
            case E -> 12;
            default -> -1;
        };
    }
    public RailShape mapRailShape(RailShape shape) {
        switch(shape) {
            case NORTH_SOUTH:
                return straightRailShape(rotateHorizontal(Face.N));
            case EAST_WEST:
                return straightRailShape(rotateHorizontal(Face.E));
            case ASCENDING_NORTH:
                return ascendingRailShape(rotateHorizontal(Face.N), shape);
            case ASCENDING_SOUTH:
                return ascendingRailShape(rotateHorizontal(Face.S), shape);
            case ASCENDING_EAST:
                return ascendingRailShape(rotateHorizontal(Face.E), shape);
            case ASCENDING_WEST:
                return ascendingRailShape(rotateHorizontal(Face.W), shape);
            case SOUTH_EAST:
                return curvedRailShape(rotateHorizontal(Face.S), rotateHorizontal(Face.E));
            case SOUTH_WEST:
                return curvedRailShape(rotateHorizontal(Face.S), rotateHorizontal(Face.W));
            case NORTH_WEST:
                return curvedRailShape(rotateHorizontal(Face.N), rotateHorizontal(Face.W));
            case NORTH_EAST:
                return curvedRailShape(rotateHorizontal(Face.N), rotateHorizontal(Face.E));
            default:
                return null;
        }
    }

    private Face rotateHorizontal(Face source) {
        Face face = map(source);
        if (face == Face.N || face == Face.S || face == Face.E || face == Face.W) {
            return face;
        }
        return null;
    }

    private static RailShape straightRailShape(Face face) {
        if (face == Face.N || face == Face.S) {
            return RailShape.NORTH_SOUTH;
        }
        if (face == Face.E || face == Face.W) {
            return RailShape.EAST_WEST;
        }
        return null;
    }

    private static RailShape ascendingRailShape(Face face, RailShape fallback) {
        if (face == null) {
            return fallback;
        }
        switch(face) {
            case N:
                return RailShape.ASCENDING_NORTH;
            case S:
                return RailShape.ASCENDING_SOUTH;
            case E:
                return RailShape.ASCENDING_EAST;
            case W:
                return RailShape.ASCENDING_WEST;
            default:
                return fallback;
        }
    }

    private static RailShape curvedRailShape(Face a, Face b) {
        if (a == null || b == null) {
            return null;
        }
        boolean north = a == Face.N || b == Face.N;
        boolean south = a == Face.S || b == Face.S;
        boolean east = a == Face.E || b == Face.E;
        boolean west = a == Face.W || b == Face.W;
        if (south && east) {
            return RailShape.SOUTH_EAST;
        }
        if (south && west) {
            return RailShape.SOUTH_WEST;
        }
        if (north && west) {
            return RailShape.NORTH_WEST;
        }
        if (north && east) {
            return RailShape.NORTH_EAST;
        }
        return null;
    }

    public enum RailShape {
        NORTH_SOUTH, EAST_WEST, ASCENDING_EAST, ASCENDING_WEST, ASCENDING_NORTH,
        ASCENDING_SOUTH, SOUTH_EAST, SOUTH_WEST, NORTH_WEST, NORTH_EAST
    }
}

