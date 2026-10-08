package art.arcane.wormholes.modded.seamless;

import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

final class DoorCollisionContext extends EntityCollisionContext {
    private final TravelMessage.DoorCollisionTarget target;

    DoorCollisionContext(TravelMessage.DoorCollisionTarget target) {
        super(false, false, -Double.MAX_VALUE, ItemStack.EMPTY, false, null);
        this.target = target;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, CollisionGetter level, BlockPos position) {
        if (position.getX() == target.x() && position.getZ() == target.z()) {
            if (state.getBlock() instanceof DoorBlock
                && (position.getY() == target.y() && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                    || position.getY() == target.y() + 1 && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER)) {
                state = state.setValue(DoorBlock.OPEN, target.open());
            } else if (state.getBlock() instanceof TrapDoorBlock && position.getY() == target.y()) {
                state = state.setValue(TrapDoorBlock.OPEN, target.open());
            }
        }
        return super.getCollisionShape(state, level, position);
    }
}
