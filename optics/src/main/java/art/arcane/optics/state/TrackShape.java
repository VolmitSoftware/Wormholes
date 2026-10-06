package art.arcane.optics.state;

import java.util.Locale;

import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.math.Face;

public enum TrackShape {
    NORTH_SOUTH,
    EAST_WEST,
    ASCENDING_EAST,
    ASCENDING_WEST,
    ASCENDING_NORTH,
    ASCENDING_SOUTH,
    SOUTH_EAST,
    SOUTH_WEST,
    NORTH_WEST,
    NORTH_EAST;

    private static final TrackShape[] VALUES = values();

    private final String serializedName;

    TrackShape() {
        serializedName = name().toLowerCase(Locale.ROOT);
    }

    public static TrackShape fromSerializedName(String name) {
        if (name == null) {
            return null;
        }
        for (TrackShape shape : VALUES) {
            if (shape.serializedName.equals(name)) {
                return shape;
            }
        }
        return null;
    }

    public String serializedName() {
        return serializedName;
    }

    public TrackShape map(AxisPermutation permutation) {
        return switch (this) {
            case NORTH_SOUTH -> straight(horizontal(permutation, Face.N));
            case EAST_WEST -> straight(horizontal(permutation, Face.E));
            case ASCENDING_NORTH -> ascending(horizontal(permutation, Face.N));
            case ASCENDING_SOUTH -> ascending(horizontal(permutation, Face.S));
            case ASCENDING_EAST -> ascending(horizontal(permutation, Face.E));
            case ASCENDING_WEST -> ascending(horizontal(permutation, Face.W));
            case SOUTH_EAST -> curved(horizontal(permutation, Face.S), horizontal(permutation, Face.E));
            case SOUTH_WEST -> curved(horizontal(permutation, Face.S), horizontal(permutation, Face.W));
            case NORTH_WEST -> curved(horizontal(permutation, Face.N), horizontal(permutation, Face.W));
            case NORTH_EAST -> curved(horizontal(permutation, Face.N), horizontal(permutation, Face.E));
        };
    }

    private TrackShape ascending(Face face) {
        if (face == null) {
            return this;
        }
        return switch (face) {
            case N -> ASCENDING_NORTH;
            case S -> ASCENDING_SOUTH;
            case E -> ASCENDING_EAST;
            case W -> ASCENDING_WEST;
            default -> this;
        };
    }

    private static Face horizontal(AxisPermutation permutation, Face source) {
        Face face = permutation.face(source);
        return face.isVertical() ? null : face;
    }

    private static TrackShape straight(Face face) {
        if (face == null) {
            return null;
        }
        return face == Face.N || face == Face.S ? NORTH_SOUTH : EAST_WEST;
    }

    private static TrackShape curved(Face a, Face b) {
        if (a == null || b == null) {
            return null;
        }
        boolean north = a == Face.N || b == Face.N;
        boolean south = a == Face.S || b == Face.S;
        boolean east = a == Face.E || b == Face.E;
        boolean west = a == Face.W || b == Face.W;
        if (south && east) {
            return SOUTH_EAST;
        }
        if (south && west) {
            return SOUTH_WEST;
        }
        if (north && west) {
            return NORTH_WEST;
        }
        if (north && east) {
            return NORTH_EAST;
        }
        return null;
    }
}
