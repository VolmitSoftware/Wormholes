package art.arcane.wormholes.menu;

import java.util.Objects;

import org.bukkit.inventory.ItemStack;

import art.arcane.wormholes.portal.VanillaTravelCost;

public final class BukkitCapturedItem implements CapturedItem {
    private final ItemStack stack;

    public BukkitCapturedItem(ItemStack stack) {
        ItemStack single = Objects.requireNonNull(stack, "stack").clone();
        single.setAmount(1);
        this.stack = single;
    }

    public ItemStack stack() {
        return stack.clone();
    }

    @Override
    public String itemId() {
        return stack.getType().getKey().toString();
    }

    @Override
    public String label() {
        return VanillaTravelCost.itemLabel(stack.getType());
    }
}
