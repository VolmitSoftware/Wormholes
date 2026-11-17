package art.arcane.wormholes.modded;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

final class MinecraftStackTags {
    private MinecraftStackTags() {
    }

    static CompoundTag read(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null ? new CompoundTag() : tag;
    }

    static void merge(ItemStack stack, CompoundTag data) {
        stack.getOrCreateTag().merge(data);
    }
}
