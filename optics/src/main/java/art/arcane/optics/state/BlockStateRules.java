package art.arcane.optics.state;

import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.math.Axis;
import art.arcane.optics.math.Face;

public final class BlockStateRules {
    private static final Face[] FACES = Face.values();
    private static final Face[] HORIZONTAL = {Face.N, Face.E, Face.S, Face.W};
    private static final String TRUE = "true";
    private static final String FALSE = "false";
    private static final String NONE = "none";

    private BlockStateRules() {
    }

    public static StateProperties apply(StateProperties properties, AxisPermutation permutation) {
        if (permutation == AxisPermutation.IDENTITY || properties.size() == 0) {
            return properties;
        }
        StateProperties result = properties;
        for (String name : properties.asMap().keySet()) {
            String mapped = mapValue(name, properties.get(name), permutation);
            if (mapped != null) {
                result = result.with(name, mapped);
            }
        }
        if (hasBooleanHorizontal(properties)) {
            return booleanConnections(properties, result, permutation);
        }
        return horizontalConnections(properties, result, permutation);
    }

    private static String mapValue(String name, String value, AxisPermutation permutation) {
        return switch (name) {
            case "facing", "vertical_direction" -> mappedFace(value, permutation);
            case "axis" -> mappedAxis(value, permutation);
            case "rotation" -> mappedRotation(value, permutation);
            case "shape" -> mappedShape(value, permutation);
            case "hinge" -> permutation.reflectsHorizontally() ? swap(value, "left", "right") : null;
            case "type" -> mappedType(value, permutation);
            case "half" -> permutation.flipsWorldUp() ? swapHalf(value) : null;
            case "face", "attachment" -> permutation.flipsWorldUp() ? swap(value, "floor", "ceiling") : null;
            case "hanging" -> permutation.flipsWorldUp() ? swap(value, TRUE, FALSE) : null;
            default -> null;
        };
    }

    private static StateProperties booleanConnections(StateProperties source, StateProperties result, AxisPermutation permutation) {
        boolean[] enabled = new boolean[FACES.length];
        for (Face face : FACES) {
            if (!TRUE.equals(source.get(name(face)))) {
                continue;
            }
            Face target = permutation.face(face);
            if (isBoolean(source.get(name(target)))) {
                enabled[target.ordinal()] = true;
            }
        }
        StateProperties connected = result;
        for (Face face : FACES) {
            if (isBoolean(source.get(name(face)))) {
                connected = connected.with(name(face), enabled[face.ordinal()] ? TRUE : FALSE);
            }
        }
        return connected;
    }

    private static StateProperties horizontalConnections(StateProperties source, StateProperties result, AxisPermutation permutation) {
        String[] targets = new String[FACES.length];
        boolean present = false;
        for (Face face : HORIZONTAL) {
            String value = source.get(name(face));
            if (value == null) {
                continue;
            }
            present = true;
            Face target = permutation.face(face);
            if (!target.isVertical() && source.get(name(target)) != null) {
                targets[target.ordinal()] = value;
            }
        }
        if (!present) {
            return result;
        }
        StateProperties connected = result;
        for (Face face : HORIZONTAL) {
            if (source.get(name(face)) != null) {
                String value = targets[face.ordinal()];
                connected = connected.with(name(face), value == null ? NONE : value);
            }
        }
        return connected;
    }

    private static boolean hasBooleanHorizontal(StateProperties properties) {
        for (Face face : HORIZONTAL) {
            if (isBoolean(properties.get(name(face)))) {
                return true;
            }
        }
        return false;
    }

    private static String mappedFace(String value, AxisPermutation permutation) {
        Face face = face(value);
        return face == null ? null : name(permutation.face(face));
    }

    private static String mappedAxis(String value, AxisPermutation permutation) {
        Axis axis = switch (value) {
            case "x" -> Axis.X;
            case "y" -> Axis.Y;
            case "z" -> Axis.Z;
            default -> null;
        };
        if (axis == null) {
            return null;
        }
        return switch (permutation.axis(axis)) {
            case X -> "x";
            case Y -> "y";
            case Z -> "z";
        };
    }

    private static String mappedRotation(String value, AxisPermutation permutation) {
        int rotation = rotation(value);
        return rotation < 0 ? null : Integer.toString(permutation.rotation16(rotation));
    }

    private static String mappedShape(String value, AxisPermutation permutation) {
        TrackShape track = TrackShape.fromSerializedName(value);
        if (track != null) {
            TrackShape mapped = track.map(permutation);
            return mapped == null ? null : mapped.serializedName();
        }
        if (!permutation.reflectsHorizontally()) {
            return null;
        }
        return switch (value) {
            case "inner_left" -> "inner_right";
            case "inner_right" -> "inner_left";
            case "outer_left" -> "outer_right";
            case "outer_right" -> "outer_left";
            default -> null;
        };
    }

    private static String mappedType(String value, AxisPermutation permutation) {
        if (permutation.reflectsHorizontally()) {
            String swapped = swap(value, "left", "right");
            if (swapped != null) {
                return swapped;
            }
        }
        return permutation.flipsWorldUp() ? swap(value, "top", "bottom") : null;
    }

    private static String swapHalf(String value) {
        String swapped = swap(value, "top", "bottom");
        return swapped != null ? swapped : swap(value, "upper", "lower");
    }

    private static String swap(String value, String first, String second) {
        if (first.equals(value)) {
            return second;
        }
        return second.equals(value) ? first : null;
    }

    private static int rotation(String value) {
        if (value.isEmpty() || value.length() > 2) {
            return -1;
        }
        int rotation = 0;
        for (int index = 0; index < value.length(); index++) {
            char digit = value.charAt(index);
            if (digit < '0' || digit > '9') {
                return -1;
            }
            rotation = rotation * 10 + (digit - '0');
        }
        return rotation < 16 ? rotation : -1;
    }

    private static boolean isBoolean(String value) {
        return TRUE.equals(value) || FALSE.equals(value);
    }

    private static Face face(String value) {
        return switch (value) {
            case "up" -> Face.U;
            case "down" -> Face.D;
            case "north" -> Face.N;
            case "south" -> Face.S;
            case "east" -> Face.E;
            case "west" -> Face.W;
            default -> null;
        };
    }

    private static String name(Face face) {
        return switch (face) {
            case U -> "up";
            case D -> "down";
            case N -> "north";
            case S -> "south";
            case E -> "east";
            case W -> "west";
        };
    }
}
