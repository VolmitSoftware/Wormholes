package art.arcane.optics.state;

import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.math.Axis;
import art.arcane.optics.math.Face;

public final class BlockStateRules {
    private static final Face[] FACES = Face.values();
    private static final String[] NAMES = {"up", "down", "north", "south", "east", "west"};
    private static final String[] AXES = {"x", "y", "z"};
    private static final Face[] AXIS_FACES = {Face.E, Face.U, Face.S};
    private static final String[] ROTATIONS = new String[16];
    private static final String[] RULES = {"facing", "vertical_direction", "axis", "rotation", "shape", "orientation",
        "hinge", "side_chain", "type", "half", "face", "attachment", "hanging"};
    private static final String[] SIDES = {"left", "right", "inner_left", "inner_right", "outer_left", "outer_right"};
    private static final String[] LEVELS = {"top", "bottom", "upper", "lower", "floor", "ceiling", "true", "false"};
    private static final String ASCENDING = "ascending_";

    static {
        for (int rotation = 0; rotation < ROTATIONS.length; rotation++) {
            ROTATIONS[rotation] = Integer.toString(rotation);
        }
    }

    private BlockStateRules() {
    }

    public static StateProperties apply(StateProperties properties, AxisPermutation permutation) {
        if (permutation == AxisPermutation.IDENTITY || properties.size() == 0) {
            return properties;
        }
        StateProperties result = properties;
        for (int index = 0; index < properties.size(); index++) {
            String value = properties.value(index);
            String mapped = switch (indexOf(RULES, properties.name(index))) {
                case -1 -> null;
                case 0, 1 -> {
                    int face = indexOf(NAMES, value);
                    yield face < 0 ? null : NAMES[permutation.face(FACES[face]).ordinal()];
                }
                case 2 -> {
                    int axis = indexOf(AXES, value);
                    yield axis < 0 ? null : AXES[permutation.face(AXIS_FACES[axis]).getAxis().ordinal()];
                }
                case 3 -> {
                    int rotation = indexOf(ROTATIONS, value);
                    yield rotation < 0 ? null : ROTATIONS[permutation.rotation16(rotation)];
                }
                case 4 -> {
                    String track = mappedTrack(value, permutation);
                    yield track != null ? track : swapped(value, permutation);
                }
                case 5 -> mappedOrientation(value, permutation);
                default -> swapped(value, permutation);
            };
            if (mapped != null) {
                result = result.with(properties.name(index), mapped);
            }
        }
        return connections(properties, result, permutation);
    }

    public static boolean affects(StateProperties properties) {
        for (int index = 0; index < properties.size(); index++) {
            if (indexOf(RULES, properties.name(index)) >= 0 || indexOf(NAMES, properties.name(index)) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static StateProperties connections(StateProperties source, StateProperties result, AxisPermutation permutation) {
        boolean booleans = false;
        for (int face = 2; face < NAMES.length; face++) {
            booleans |= isBoolean(source.get(NAMES[face]));
        }
        String[] moved = new String[NAMES.length];
        boolean present = false;
        for (Face face : FACES) {
            String value = source.get(NAMES[face.ordinal()]);
            if (connects(face, value, booleans)) {
                present = true;
                Face target = permutation.face(face);
                if (connects(target, source.get(NAMES[target.ordinal()]), booleans)) {
                    moved[target.ordinal()] = value;
                }
            }
        }
        if (!present) {
            return result;
        }
        StateProperties connected = result;
        for (Face face : FACES) {
            if (connects(face, source.get(NAMES[face.ordinal()]), booleans)) {
                String value = moved[face.ordinal()];
                connected = connected.with(NAMES[face.ordinal()], value != null ? value : booleans ? "false" : "none");
            }
        }
        return connected;
    }

    private static boolean connects(Face face, String value, boolean booleans) {
        return value != null && (booleans ? isBoolean(value) : !face.isVertical());
    }

    private static boolean isBoolean(String value) {
        return "true".equals(value) || "false".equals(value);
    }

    private static String mappedTrack(String value, AxisPermutation permutation) {
        int split = value.indexOf('_');
        int second = split < 0 ? -1 : indexOf(NAMES, value.substring(split + 1));
        if (second < 0) {
            return null;
        }
        Face end = permutation.face(FACES[second]);
        if (value.startsWith(ASCENDING)) {
            return end.isVertical() ? null : ASCENDING.concat(NAMES[(permutation.flipsWorldUp() ? end.reverse() : end).ordinal()]);
        }
        int first = indexOf(NAMES, value.substring(0, split));
        if (first < 0) {
            return null;
        }
        Face start = permutation.face(FACES[first]);
        if (start.isVertical() || end.isVertical()) {
            return null;
        }
        if (start.getAxis() == end.getAxis()) {
            return start.getAxis() == Axis.Z ? "north_south" : "east_west";
        }
        return start.getAxis() == Axis.Z ? join(start, end) : join(end, start);
    }

    private static String mappedOrientation(String value, AxisPermutation permutation) {
        int split = value.indexOf('_');
        int front = split < 0 ? -1 : indexOf(NAMES, value.substring(0, split));
        int top = split < 0 ? -1 : indexOf(NAMES, value.substring(split + 1));
        if (front < 0 || top < 0) {
            return null;
        }
        Face mappedFront = permutation.face(FACES[front]);
        return join(mappedFront, mappedFront.isVertical() ? permutation.face(FACES[top]) : Face.U);
    }

    private static String join(Face first, Face second) {
        return NAMES[first.ordinal()].concat("_").concat(NAMES[second.ordinal()]);
    }

    private static String swapped(String value, AxisPermutation permutation) {
        String sided = permutation.reflectsHorizontally() ? opposite(value, SIDES) : value;
        String levelled = permutation.flipsWorldUp() ? opposite(sided, LEVELS) : sided;
        return levelled.equals(value) ? null : levelled;
    }

    private static String opposite(String value, String[] pairs) {
        int index = indexOf(pairs, value);
        return index < 0 ? value : pairs[index ^ 1];
    }

    private static int indexOf(String[] values, String value) {
        for (int index = 0; index < values.length; index++) {
            if (values[index].equals(value)) {
                return index;
            }
        }
        return -1;
    }
}
