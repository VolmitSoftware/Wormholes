package art.arcane.wormholes.modded.menu;

import art.arcane.wormholes.menu.CapturedItem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.Objects;

public final class MinecraftCapturedItem implements CapturedItem {
    private final ItemStack stack;

    public MinecraftCapturedItem(ItemStack stack) {
        if (Objects.requireNonNull(stack, "stack").isEmpty()) {
            throw new IllegalArgumentException("A captured item must not be empty");
        }
        this.stack = stack.copyWithCount(1);
    }

    public static String label(Item item) {
        String[] words = BuiltInRegistries.ITEM.getKey(item).getPath().toLowerCase(Locale.ROOT).split("_");
        StringBuilder label = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!label.isEmpty()) {
                label.append(' ');
            }
            label.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return label.toString();
    }

    public ItemStack stack() {
        return stack.copy();
    }

    @Override
    public String itemId() {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    @Override
    public String label() {
        return label(stack.getItem());
    }
}
