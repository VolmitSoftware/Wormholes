package art.arcane.wormholes.modded;

import com.mojang.math.Transformation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.state.BlockState;

final class MinecraftTransformTags {
    private MinecraftTransformTags() {
    }

    static void transformation(CompoundTag data, Transformation transformation) {
        Transformation.EXTENDED_CODEC.encodeStart(NbtOps.INSTANCE, transformation).result()
            .ifPresent(tag -> data.put("transformation", tag));
    }

    static void blockState(CompoundTag data, BlockState state) {
        data.put("block_state", NbtUtils.writeBlockState(state));
    }
}
