package art.arcane.wormholes.modded;

import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public final class MinecraftDyedItems {
    private MinecraftDyedItems() {
    }

    public static Item pane(DyeColor color) {
        return Items.STAINED_GLASS_PANE.pick(color);
    }

    public static Item glass(DyeColor color) {
        return Items.STAINED_GLASS.pick(color);
    }

    public static Item dye(DyeColor color) {
        return Items.DYE.pick(color);
    }

    public static Item concrete(DyeColor color) {
        return Items.CONCRETE.pick(color);
    }

    public static Item wool(DyeColor color) {
        return Items.WOOL.pick(color);
    }

    public static Block concreteBlock(DyeColor color) {
        return Blocks.CONCRETE.pick(color);
    }

    public static Block glassBlock(DyeColor color) {
        return Blocks.STAINED_GLASS.pick(color);
    }

    public static Block woolBlock(DyeColor color) {
        return Blocks.WOOL.pick(color);
    }
}
