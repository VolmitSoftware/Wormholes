package art.arcane.optics.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.optics.math.Face;

final class BlockStateRulesTest {
    private static final AxisPermutation NORTH_MIRROR = AxisPermutation.mirror(Frame.canonical(Face.N), QuarterTurn.DEGREES_0);
    private static final AxisPermutation NORTH_MIRROR_HALF_TURN = AxisPermutation.mirror(Frame.canonical(Face.N), QuarterTurn.DEGREES_180);
    private static final AxisPermutation EAST_MIRROR = AxisPermutation.mirror(Frame.canonical(Face.E), QuarterTurn.DEGREES_0);
    private static final AxisPermutation QUARTER_TURN = AxisPermutation.between(Frame.canonical(Face.N), Frame.canonical(Face.E));
    private static final AxisPermutation TILT = AxisPermutation.between(Frame.canonical(Face.N), Frame.canonical(Face.U));
    private static final AxisPermutation FLOOR_MIRROR = AxisPermutation.mirror(Frame.canonical(Face.U), QuarterTurn.DEGREES_0);

    @Test
    void chiralityCases() {
        assertRule("facing=north,half=bottom,shape=inner_left", NORTH_MIRROR, "facing=south,half=bottom,shape=inner_right");
        assertRule("facing=north,half=bottom,shape=outer_left", QUARTER_TURN, "facing=east,half=bottom,shape=outer_left");
        assertRule("facing=north,half=bottom,shape=straight", NORTH_MIRROR, "facing=south,half=bottom,shape=straight");
        assertRule("facing=north,half=lower,hinge=left,open=false,powered=false", NORTH_MIRROR, "facing=south,half=lower,hinge=right,open=false,powered=false");
        assertRule("facing=north,half=lower,hinge=left,open=false,powered=false", QUARTER_TURN, "facing=east,half=lower,hinge=left,open=false,powered=false");
        assertRule("facing=north,type=left,waterlogged=false", NORTH_MIRROR, "facing=south,type=right,waterlogged=false");
        assertRule("facing=north,type=single,waterlogged=false", NORTH_MIRROR, "facing=south,type=single,waterlogged=false");
        assertRule("facing=east,half=lower,hinge=left", EAST_MIRROR, "facing=west,half=lower,hinge=right");
        assertRule("facing=north,half=bottom,shape=inner_left", NORTH_MIRROR_HALF_TURN, "facing=south,half=top,shape=inner_left");
    }

    @Test
    void railsFollowTheirEndpointsWithoutASecondFlip() {
        assertRule("shape=south_east", NORTH_MIRROR, "shape=north_east");
        assertRule("shape=north_east,waterlogged=false", EAST_MIRROR, "shape=north_west,waterlogged=false");
        assertRule("powered=true,shape=north_south", QUARTER_TURN, "powered=true,shape=east_west");
        assertRule("shape=north_south", TILT, "shape=north_south");
        assertRule("shape=ascending_north", TILT, "shape=ascending_north");
    }

    @Test
    void wallHeightsAndRedstoneConnectionsFollowTheFrame() {
        assertRule("east=tall,north=low,south=none,up=true,waterlogged=false,west=none", QUARTER_TURN,
            "east=low,north=none,south=tall,up=true,waterlogged=false,west=none");
        assertRule("east=side,north=up,power=7,south=none,west=none", QUARTER_TURN, "east=up,north=none,power=7,south=side,west=none");
        assertRule("east=none,north=low,south=none,up=false,west=none", TILT, "east=none,north=none,south=none,up=false,west=none");
    }

    @Test
    void sixteenStepRotationsFollowTheHorizontalTurn() {
        assertRule("rotation=9", QUARTER_TURN, "rotation=13");
        assertRule("rotation=9", NORTH_MIRROR, "rotation=15");
        assertRule("rotation=9", EAST_MIRROR, "rotation=7");
        assertRule("rotation=9", NORTH_MIRROR_HALF_TURN, "rotation=1");
        assertRule("rotation=9", TILT, "rotation=9");
    }

    @Test
    void facingAndAxisFollowEveryPermutation() {
        assertRule("facing=north,triggered=false", TILT, "facing=up,triggered=false");
        assertRule("axis=z", QUARTER_TURN, "axis=x");
        assertRule("axis=y", QUARTER_TURN, "axis=y");
        assertRule("axis=y", TILT, "axis=z");
        for (int index = 0; index < 48; index++) {
            AxisPermutation permutation = AxisPermutation.ofIndex(index);
            for (Face face : Face.values()) {
                StateProperties source = StateProperties.of(Map.of("facing", name(face)));
                assertEquals(name(permutation.face(face)), BlockStateRules.apply(source, permutation).get("facing"));
            }
        }
    }

    @Test
    void booleanConnectionsFollowEveryPermutationForSixFacedBlocks() {
        for (int index = 0; index < 48; index++) {
            AxisPermutation permutation = AxisPermutation.ofIndex(index);
            for (int mask = 0; mask < 64; mask++) {
                Map<String, String> values = new LinkedHashMap<String, String>();
                for (Face face : Face.values()) {
                    values.put(name(face), Boolean.toString((mask & (1 << face.ordinal())) != 0));
                }
                StateProperties mapped = BlockStateRules.apply(StateProperties.of(values), permutation);
                for (Face face : Face.values()) {
                    assertEquals(values.get(name(face)), mapped.get(name(permutation.face(face))), permutation + " mask " + mask + " " + face);
                }
            }
        }
    }

    @Test
    void connectionsThatLandOnMissingFacesAreDropped() {
        assertRule("east=false,north=true,south=false,waterlogged=false,west=true", TILT, "east=false,north=false,south=false,waterlogged=false,west=true");
        assertRule("east=false,north=false,south=true,up=true,west=false", TILT, "east=false,north=false,south=true,up=false,west=false");
        assertRule("east=false,north=false,south=true,up=false,west=false", TILT, "east=false,north=false,south=false,up=false,west=false");
    }

    @Test
    void verticalFlipsSwapHalvesAndAttachments() {
        assertRule("type=bottom,waterlogged=false", FLOOR_MIRROR, "type=top,waterlogged=false");
        assertRule("type=double,waterlogged=false", FLOOR_MIRROR, "type=double,waterlogged=false");
        assertRule("facing=north,half=bottom,shape=inner_left", FLOOR_MIRROR, "facing=north,half=top,shape=inner_left");
        assertRule("facing=east,half=top,open=false", FLOOR_MIRROR, "facing=east,half=bottom,open=false");
        assertRule("facing=north,half=upper,hinge=left", FLOOR_MIRROR, "facing=north,half=lower,hinge=left");
        assertRule("face=floor,facing=north,powered=false", FLOOR_MIRROR, "face=ceiling,facing=north,powered=false");
        assertRule("face=wall,facing=north,powered=false", FLOOR_MIRROR, "face=wall,facing=north,powered=false");
        assertRule("attachment=ceiling,facing=north", FLOOR_MIRROR, "attachment=floor,facing=north");
        assertRule("hanging=true,waterlogged=false", FLOOR_MIRROR, "hanging=false,waterlogged=false");
        assertRule("thickness=tip,vertical_direction=up", FLOOR_MIRROR, "thickness=tip,vertical_direction=down");
        assertRule("type=bottom,waterlogged=false", QUARTER_TURN, "type=bottom,waterlogged=false");
        assertRule("hanging=true", NORTH_MIRROR, "hanging=true");
    }

    @Test
    void identityAndUnknownValuesAreLeftAlone() {
        StateProperties source = parse("facing=north,half=bottom,shape=inner_left");
        assertSame(source, BlockStateRules.apply(source, AxisPermutation.IDENTITY));
        assertRule("facing=sideways,level=3", QUARTER_TURN, "facing=sideways,level=3");
        assertRule("rotation=many", QUARTER_TURN, "rotation=many");
        assertRule("age=4,distance=2", QUARTER_TURN, "age=4,distance=2");
    }

    private static void assertRule(String source, AxisPermutation permutation, String expected) {
        assertEquals(parse(expected), BlockStateRules.apply(parse(source), permutation), source + " via " + permutation);
    }

    private static StateProperties parse(String text) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (String pair : text.split(",")) {
            String[] parts = pair.split("=", 2);
            values.put(parts[0], parts[1]);
        }
        return StateProperties.of(values);
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
