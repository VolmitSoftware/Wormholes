package art.arcane.wormholes.render;

import org.bukkit.block.BlockFace;

import art.arcane.wormholes.util.Direction;

/** Vanilla 16-step rotation index (0 = south, rising clockwise seen from above) with rotate and reflect. */
final class BlockRotation16 {
    private static final int STEPS = 16;
    private static final BlockFace[] FACES = {
        BlockFace.SOUTH, BlockFace.SOUTH_SOUTH_WEST, BlockFace.SOUTH_WEST, BlockFace.WEST_SOUTH_WEST,
        BlockFace.WEST, BlockFace.WEST_NORTH_WEST, BlockFace.NORTH_WEST, BlockFace.NORTH_NORTH_WEST,
        BlockFace.NORTH, BlockFace.NORTH_NORTH_EAST, BlockFace.NORTH_EAST, BlockFace.EAST_NORTH_EAST,
        BlockFace.EAST, BlockFace.EAST_SOUTH_EAST, BlockFace.SOUTH_EAST, BlockFace.SOUTH_SOUTH_EAST
    };

    private BlockRotation16() {
    }

    static int index(BlockFace face) {
        for (int index = 0; index < FACES.length; index++) {
            if (FACES[index] == face) {
                return index;
            }
        }
        return -1;
    }

    static BlockFace face(int index) {
        return FACES[Math.floorMod(index, STEPS)];
    }

    static int rotate(int index, int quarterTurnsClockwise) {
        return Math.floorMod(index + (quarterTurnsClockwise * 4), STEPS);
    }

    /**
     * Mirrors across the vertical plane whose horizontal normal is {@code mirrorNormal}. A plane with a
     * vertical normal is horizontal and leaves every horizontal rotation untouched.
     */
    static int reflect(int index, Direction mirrorNormal) {
        if (mirrorNormal.isVertical()) {
            return Math.floorMod(index, STEPS);
        }
        int fixedAxisIndex = mirrorNormal == Direction.N || mirrorNormal == Direction.S ? 4 : 0;
        return Math.floorMod((2 * fixedAxisIndex) - index, STEPS);
    }
}
