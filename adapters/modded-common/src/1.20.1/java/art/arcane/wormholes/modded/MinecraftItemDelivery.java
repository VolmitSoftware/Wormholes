package art.arcane.wormholes.modded;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public final class MinecraftItemDelivery {
    private MinecraftItemDelivery() {
    }

    public static void drop(ServerPlayer player, ItemStack item) {
        player.drop(item, false, false);
    }

    public static void returnToInventory(Inventory inventory, ItemStack item) {
        inventory.placeItemBackInInventory(item, false);
    }
}
