package art.arcane.optics.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.DirectionMapping;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.optics.math.Face;

final class TrackShapeTest {
    @Test
    void shapesMirrorTheLegacyRailShapeNamesAndSerializedForms() {
        assertEquals(DirectionMapping.RailShape.values().length, TrackShape.values().length);
        for (TrackShape shape : TrackShape.values()) {
            assertEquals(DirectionMapping.RailShape.values()[shape.ordinal()].name(), shape.name());
            assertSame(shape, TrackShape.fromSerializedName(shape.serializedName()));
        }
        assertEquals("ascending_north", TrackShape.ASCENDING_NORTH.serializedName());
        assertNull(TrackShape.fromSerializedName("straight"));
        assertNull(TrackShape.fromSerializedName(null));
    }

    @Test
    void everyPermutationMapsEveryShapeLikeTheLegacyRailMapping() {
        for (int index = 0; index < 48; index++) {
            AxisPermutation permutation = AxisPermutation.ofIndex(index);
            DirectionMapping mapping = DirectionMapping.axes(permutation.x(), permutation.y(), permutation.z());
            for (TrackShape shape : TrackShape.values()) {
                DirectionMapping.RailShape expected = mapping.mapRailShape(DirectionMapping.RailShape.valueOf(shape.name()));
                TrackShape actual = shape.map(permutation);
                assertEquals(expected == null ? null : expected.name(), actual == null ? null : actual.name(),
                    permutation + " " + shape);
            }
        }
    }

    @Test
    void mirroredCurveFollowsItsEndpointsAndQuarterTurnsRotateStraights() {
        AxisPermutation northMirror = AxisPermutation.mirror(Frame.canonical(Face.N), QuarterTurn.DEGREES_0);
        assertEquals(TrackShape.NORTH_EAST, TrackShape.SOUTH_EAST.map(northMirror));
        AxisPermutation quarterTurn = AxisPermutation.between(Frame.canonical(Face.N), Frame.canonical(Face.E));
        assertEquals(TrackShape.EAST_WEST, TrackShape.NORTH_SOUTH.map(quarterTurn));
        assertEquals(TrackShape.ASCENDING_EAST, TrackShape.ASCENDING_NORTH.map(quarterTurn));
        AxisPermutation tilt = AxisPermutation.between(Frame.canonical(Face.N), Frame.canonical(Face.U));
        assertNull(TrackShape.NORTH_SOUTH.map(tilt));
        assertEquals(TrackShape.ASCENDING_NORTH, TrackShape.ASCENDING_NORTH.map(tilt));
    }
}
