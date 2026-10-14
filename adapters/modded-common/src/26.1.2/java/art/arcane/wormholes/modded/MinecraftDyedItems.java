package art.arcane.wormholes.modded;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
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
        return item(color, "_dye");
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
        return BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(color.getSerializedName() + suffix));
    }

    private static Block block(DyeColor color, String suffix) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(color.getSerializedName() + suffix));
    }
}
