package art.arcane.optics.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.optics.math.Face;

final class TrackShapeTest {
    @Test
    void serializedNamesRoundTrip() {
        for (TrackShape shape : TrackShape.values()) {
            assertSame(shape, TrackShape.fromSerializedName(shape.serializedName()));
        }
        assertEquals("ascending_north", TrackShape.ASCENDING_NORTH.serializedName());
        assertNull(TrackShape.fromSerializedName("straight"));
        assertNull(TrackShape.fromSerializedName(null));
    }

    @Test
    void levelPermutationsCarryTrackEndpointsToTheirImages() {
        for (int index = 0; index < 48; index++) {
            AxisPermutation permutation = AxisPermutation.ofIndex(index);
            if (!permutation.y().isVertical()) {
                continue;
            }
            for (TrackShape shape : TrackShape.values()) {
                Face[] ends = ends(shape);
                Face[] mapped = ends(shape.map(permutation));
                Face first = permutation.face(ends[0]);
                Face second = permutation.face(ends[1]);
                boolean ascending = shape.name().startsWith("ASCENDING");
                boolean matches = (mapped[0] == first && mapped[1] == second) || (!ascending && mapped[0] == second && mapped[1] == first);
                assertTrue(matches, permutation + " " + shape + " -> " + shape.map(permutation));
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

    private static Face[] ends(TrackShape shape) {
        return switch (shape) {
            case NORTH_SOUTH -> new Face[] {Face.N, Face.S};
            case EAST_WEST -> new Face[] {Face.E, Face.W};
            case ASCENDING_EAST -> new Face[] {Face.E, Face.W};
            case ASCENDING_WEST -> new Face[] {Face.W, Face.E};
            case ASCENDING_NORTH -> new Face[] {Face.N, Face.S};
            case ASCENDING_SOUTH -> new Face[] {Face.S, Face.N};
            case SOUTH_EAST -> new Face[] {Face.S, Face.E};
            case SOUTH_WEST -> new Face[] {Face.S, Face.W};
            case NORTH_WEST -> new Face[] {Face.N, Face.W};
            case NORTH_EAST -> new Face[] {Face.N, Face.E};
        };
    }
}
