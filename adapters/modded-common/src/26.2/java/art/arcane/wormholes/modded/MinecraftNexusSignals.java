package art.arcane.wormholes.modded;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class MinecraftNexusSignals {
    private MinecraftNexusSignals() {
    }

    public static boolean powered(BlockState block) {
        return block.hasProperty(RedStoneWireBlock.POWER) && block.getValue(RedStoneWireBlock.POWER) > 0;
    }

    public static void output(ServerLevel level, BlockPos position, BlockState block, int signal) {
        if (block.getBlock() instanceof RedStoneWireBlock && block.getValue(RedStoneWireBlock.POWER) != signal) {
            level.setBlock(position, block.setValue(RedStoneWireBlock.POWER, signal), 2);
        }
    }
}
