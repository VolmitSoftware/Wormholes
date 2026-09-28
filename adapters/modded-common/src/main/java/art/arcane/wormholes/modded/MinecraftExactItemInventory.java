package art.arcane.wormholes.modded;

import art.arcane.wormholes.portal.ExactItemPayment;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public record MinecraftExactItemInventory(ServerPlayer player) implements ExactItemPayment.Inventory<ItemStack> {
    @Override
    public int storageSize() {
        return Inventory.INVENTORY_SIZE;
    }

    @Override
    public ItemStack get(int slot) {
        return player.getInventory().getItem(slot);
    }

    @Override
    public void set(int slot, ItemStack item) {
        player.getInventory().setItem(slot, item);
    }

    @Override
    public ItemStack empty() {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean matches(ItemStack stack, ItemStack template) {
        return !stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, template);
    }

    @Override
    public int count(ItemStack item) {
        return item.getCount();
    }

    @Override
    public ItemStack copy(ItemStack item, int count) {
        return item.copyWithCount(count);
    }

    @Override
    public int maximumStackSize(ItemStack item) {
        return item.getMaxStackSize();
    }

    @Override
    public void give(ItemStack item) {
        player.getInventory().add(item);
        if (!item.isEmpty()) {
            player.drop(item, false);
        }
    }
}
