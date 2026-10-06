package art.arcane.optics.frame;

import art.arcane.optics.math.Face;

public final class DirectionMapping {
    private final AxisPermutation permutation;

    private DirectionMapping(AxisPermutation permutation) {
        this.permutation = permutation;
    }

    public static DirectionMapping axes(Face x, Face y, Face z) {
        if (x.getAxis() == y.getAxis() || x.getAxis() == z.getAxis() || y.getAxis() == z.getAxis()) {
            throw new IllegalArgumentException("Mapped axes must be perpendicular");
        }
        return new DirectionMapping(AxisPermutation.of(x, y, z));
    }

    public static DirectionMapping between(Frame fromFrame, Frame toFrame, double[] scratch3) {
        return new DirectionMapping(AxisPermutation.between(fromFrame, toFrame));
    }

    public static DirectionMapping mirror(Frame frame, int quarterTurns, double[] scratch3) {
        return new DirectionMapping(AxisPermutation.mirror(frame, QuarterTurn.of(quarterTurns)));
    }

    public int quarterTurnsClockwise() {
        return permutation.quarterTurnsClockwise();
    }

    public boolean reflects() {
        return permutation.reflectsHorizontally();
    }

    public int mapRotation(int index) {
        return permutation.rotation16(index);
    }

    public Face map(Face source) {
        return permutation.face(source);
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

