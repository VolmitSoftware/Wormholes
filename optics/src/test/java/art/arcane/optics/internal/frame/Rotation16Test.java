package art.arcane.optics.internal.frame;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import art.arcane.optics.math.Face;

final class Rotation16Test {
    private static final int SOUTH = 0;
    private static final int WEST = 4;
    private static final int NORTH_NORTH_WEST = 7;
    private static final int NORTH = 8;
    private static final int NORTH_NORTH_EAST = 9;
    private static final int EAST = 12;

    @Test
    void quarterTurnAdvancesByFourAndWraps() {
        assertEquals(4, Rotation16.rotate(0, 1));
        assertEquals(1, Rotation16.rotate(13, 1));
        assertEquals(0, Rotation16.rotate(0, 4));
        assertEquals(12, Rotation16.rotate(0, -1));
    }

    @Test
    void reflectionAcrossNorthSouthPlaneSwapsEastAndWest() {
        assertEquals(WEST, Rotation16.reflect(EAST, Face.E));
        assertEquals(NORTH_NORTH_WEST, Rotation16.reflect(NORTH_NORTH_EAST, Face.E));
        assertEquals(SOUTH, Rotation16.reflect(NORTH, Face.S));
    }

    @Test
    void reflectionAcrossAHorizontalPlaneLeavesHorizontalRotationsAlone() {
        assertEquals(NORTH_NORTH_EAST, Rotation16.reflect(NORTH_NORTH_EAST, Face.U));
    }
}
