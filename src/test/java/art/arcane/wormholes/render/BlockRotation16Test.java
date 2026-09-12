package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.util.Direction;

final class BlockRotation16Test {
    @Test
    void indexRoundTripsAllSixteenFaces() {
        for (int index = 0; index < 16; index++) {
            assertEquals(index, BlockRotation16.index(BlockRotation16.face(index)));
        }
        assertEquals(-1, BlockRotation16.index(BlockFace.UP));
    }

    @Test
    void quarterTurnAdvancesByFourAndWraps() {
        assertEquals(4, BlockRotation16.rotate(0, 1));
        assertEquals(1, BlockRotation16.rotate(13, 1));
        assertEquals(0, BlockRotation16.rotate(0, 4));
        assertEquals(12, BlockRotation16.rotate(0, -1));
    }

    @Test
    void reflectionAcrossNorthSouthPlaneSwapsEastAndWest() {
        assertEquals(BlockRotation16.index(BlockFace.WEST),
            BlockRotation16.reflect(BlockRotation16.index(BlockFace.EAST), Direction.E));
        assertEquals(BlockRotation16.index(BlockFace.NORTH_NORTH_WEST),
            BlockRotation16.reflect(BlockRotation16.index(BlockFace.NORTH_NORTH_EAST), Direction.E));
        assertEquals(BlockRotation16.index(BlockFace.SOUTH),
            BlockRotation16.reflect(BlockRotation16.index(BlockFace.NORTH), Direction.S));
    }

    @Test
    void reflectionAcrossAHorizontalPlaneLeavesHorizontalRotationsAlone() {
        assertEquals(BlockRotation16.index(BlockFace.NORTH_NORTH_EAST),
            BlockRotation16.reflect(BlockRotation16.index(BlockFace.NORTH_NORTH_EAST), Direction.U));
    }
}
