package art.arcane.wormholes.render;

import org.bukkit.block.BlockFace;


/** Vanilla 16-step rotation index (0 = south, rising clockwise seen from above) with rotate and reflect. */
final class BukkitBlockRotation16 {
    private static final int STEPS = 16;
    private static final BlockFace[] FACES = {
        BlockFace.SOUTH, BlockFace.SOUTH_SOUTH_WEST, BlockFace.SOUTH_WEST, BlockFace.WEST_SOUTH_WEST,
        BlockFace.WEST, BlockFace.WEST_NORTH_WEST, BlockFace.NORTH_WEST, BlockFace.NORTH_NORTH_WEST,
        BlockFace.NORTH, BlockFace.NORTH_NORTH_EAST, BlockFace.NORTH_EAST, BlockFace.EAST_NORTH_EAST,
        BlockFace.EAST, BlockFace.EAST_SOUTH_EAST, BlockFace.SOUTH_EAST, BlockFace.SOUTH_SOUTH_EAST
    };

    private BukkitBlockRotation16() {
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

}
