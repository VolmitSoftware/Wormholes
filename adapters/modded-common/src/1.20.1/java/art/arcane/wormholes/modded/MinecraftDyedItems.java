package art.arcane.wormholes.modded;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

public final class MinecraftDyedItems {
    private MinecraftDyedItems() {
    }

    public static Item pane(DyeColor color) {
        return item(color, "_stained_glass_pane");
    }

    public static Item glass(DyeColor color) {
        return item(color, "_stained_glass");
    }

    public static Item dye(DyeColor color) {
        return DyeItem.byColor(color);
    }

    public static Item concrete(DyeColor color) {
        return item(color, "_concrete");
    }

    public static Item wool(DyeColor color) {
        return item(color, "_wool");
    }

    public static Block concreteBlock(DyeColor color) {
        return block(color, "_concrete");
    }

    public static Block glassBlock(DyeColor color) {
        return block(color, "_stained_glass");
    }

    public static Block woolBlock(DyeColor color) {
        return block(color, "_wool");
    }

    private static Item item(DyeColor color, String suffix) {
        return BuiltInRegistries.ITEM.get(new ResourceLocation("minecraft", color.getName() + suffix));
    }

    private static Block block(DyeColor color, String suffix) {
        return BuiltInRegistries.BLOCK.get(new ResourceLocation("minecraft", color.getName() + suffix));
    }
}
