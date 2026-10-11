package art.arcane.wormholes.modded;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class MinecraftNexusSignals {
    private MinecraftNexusSignals() {
    }

    public static boolean powered(BlockState block) {
        return block.hasProperty(RedstoneWireBlock.POWER) && block.getValue(RedstoneWireBlock.POWER) > 0;
    }

    public static void output(ServerLevel level, BlockPos position, BlockState block, int signal) {
        if (block.getBlock() instanceof RedstoneWireBlock && block.getValue(RedstoneWireBlock.POWER) != signal) {
            level.setBlock(position, block.setValue(RedstoneWireBlock.POWER, signal), 2);
        }
    }
}
