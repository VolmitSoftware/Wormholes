package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import art.arcane.optics.math.Face;
import art.arcane.optics.frame.Rotation16;

final class BlockRotation16Test {
    @Test
    void indexRoundTripsAllSixteenFaces() {
        for (int index = 0; index < 16; index++) {
            assertEquals(index, BukkitBlockRotation16.index(BukkitBlockRotation16.face(index)));
        }
        assertEquals(-1, BukkitBlockRotation16.index(BlockFace.UP));
    }

    @Test
    void quarterTurnAdvancesByFourAndWraps() {
        assertEquals(4, Rotation16.rotate(0, 1));
        assertEquals(1, Rotation16.rotate(13, 1));
        assertEquals(0, Rotation16.rotate(0, 4));
        assertEquals(12, Rotation16.rotate(0, -1));
    }

    @Test
    void reflectionAcrossNorthSouthPlaneSwapsEastAndWest() {
        assertEquals(BukkitBlockRotation16.index(BlockFace.WEST),
            Rotation16.reflect(BukkitBlockRotation16.index(BlockFace.EAST), Face.E));
        assertEquals(BukkitBlockRotation16.index(BlockFace.NORTH_NORTH_WEST),
            Rotation16.reflect(BukkitBlockRotation16.index(BlockFace.NORTH_NORTH_EAST), Face.E));
        assertEquals(BukkitBlockRotation16.index(BlockFace.SOUTH),
            Rotation16.reflect(BukkitBlockRotation16.index(BlockFace.NORTH), Face.S));
    }

    @Test
    void reflectionAcrossAHorizontalPlaneLeavesHorizontalRotationsAlone() {
        assertEquals(BukkitBlockRotation16.index(BlockFace.NORTH_NORTH_EAST),
            Rotation16.reflect(BukkitBlockRotation16.index(BlockFace.NORTH_NORTH_EAST), Face.U));
    }
}
