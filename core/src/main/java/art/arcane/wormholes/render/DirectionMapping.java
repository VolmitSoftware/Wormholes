package art.arcane.wormholes.render;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.util.Direction;

public final class DirectionMapping {
    private static final int HANDEDNESS_UNCOMPUTED = Integer.MIN_VALUE;

    private final PortalFrame fromFrame;
    private final PortalFrame toFrame;
    private final PortalFrame mirrorFrame;
    private final int quarterTurns;
    private final double[] scratch3;
    private int imageQuarterTurns;
    private boolean reflects;

    private DirectionMapping(PortalFrame fromFrame, PortalFrame toFrame, PortalFrame mirrorFrame, int quarterTurns, double[] scratch3) {
        this.fromFrame = fromFrame;
        this.toFrame = toFrame;
        this.mirrorFrame = mirrorFrame;
        this.quarterTurns = quarterTurns;
        this.scratch3 = scratch3;
        this.imageQuarterTurns = HANDEDNESS_UNCOMPUTED;
        this.reflects = false;
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
        int reflected = reflects() ? BlockRotation16.reflect(index, Direction.E) : index;
        return BlockRotation16.rotate(reflected, quarterTurnsClockwise());
    }

    private void computeHandedness() {
        int southIndex = rotationIndex(map(Direction.S));
        int eastIndex = rotationIndex(map(Direction.E));
        if (southIndex < 0 || eastIndex < 0) {
            imageQuarterTurns = 0;
            reflects = false;
            return;
        }
        imageQuarterTurns = southIndex / 4;
        reflects = eastIndex != BlockRotation16.rotate(12, imageQuarterTurns);
    }

    public static DirectionMapping between(PortalFrame fromFrame, PortalFrame toFrame, double[] scratch3) {
        return new DirectionMapping(fromFrame, toFrame, null, 0, scratch3);
    }

    public static DirectionMapping mirror(PortalFrame frame, int quarterTurns, double[] scratch3) {
        return new DirectionMapping(null, null, frame, quarterTurns, scratch3);
    }

    public Direction map(Direction source) {
        if(mirrorFrame != null) {
            PortalCoordMap.mirrorSourceToDisplayVectorInto(source.x(), source.y(), source.z(), mirrorFrame, quarterTurns, scratch3);
            return Direction.closest(scratch3[0], scratch3[1], scratch3[2]);
        }
        return fromFrame.transformDirection(source, toFrame, scratch3);
    }
    private static int rotationIndex(Direction direction) {
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
                return straightRailShape(rotateHorizontal(Direction.N));
            case EAST_WEST:
                return straightRailShape(rotateHorizontal(Direction.E));
            case ASCENDING_NORTH:
                return ascendingRailShape(rotateHorizontal(Direction.N), shape);
            case ASCENDING_SOUTH:
                return ascendingRailShape(rotateHorizontal(Direction.S), shape);
            case ASCENDING_EAST:
                return ascendingRailShape(rotateHorizontal(Direction.E), shape);
            case ASCENDING_WEST:
                return ascendingRailShape(rotateHorizontal(Direction.W), shape);
            case SOUTH_EAST:
                return curvedRailShape(rotateHorizontal(Direction.S), rotateHorizontal(Direction.E));
            case SOUTH_WEST:
                return curvedRailShape(rotateHorizontal(Direction.S), rotateHorizontal(Direction.W));
            case NORTH_WEST:
                return curvedRailShape(rotateHorizontal(Direction.N), rotateHorizontal(Direction.W));
            case NORTH_EAST:
                return curvedRailShape(rotateHorizontal(Direction.N), rotateHorizontal(Direction.E));
            default:
                return null;
        }
    }

    private Direction rotateHorizontal(Direction source) {
        Direction face = map(source);
        if (face == Direction.N || face == Direction.S || face == Direction.E || face == Direction.W) {
            return face;
        }
        return null;
    }

    private static RailShape straightRailShape(Direction face) {
        if (face == Direction.N || face == Direction.S) {
            return RailShape.NORTH_SOUTH;
        }
        if (face == Direction.E || face == Direction.W) {
            return RailShape.EAST_WEST;
        }
        return null;
    }

    private static RailShape ascendingRailShape(Direction face, RailShape fallback) {
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

    private static RailShape curvedRailShape(Direction a, Direction b) {
        if (a == null || b == null) {
            return null;
        }
        boolean north = a == Direction.N || b == Direction.N;
        boolean south = a == Direction.S || b == Direction.S;
        boolean east = a == Direction.E || b == Direction.E;
        boolean west = a == Direction.W || b == Direction.W;
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

