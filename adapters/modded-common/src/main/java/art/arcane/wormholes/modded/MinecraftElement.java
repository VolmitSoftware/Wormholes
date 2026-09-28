package art.arcane.wormholes.modded;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class MinecraftElement {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final int LORE_WRAP_VISIBLE_WIDTH = 42;

    private final String id;
    private final List<String> lore = new ArrayList<>();
    private Item material = Items.AIR;
    private boolean enchanted;
    private String name;
    private int count = 1;
    private ItemStack baseItemStack;
    private Consumer<MinecraftElement> left;
    private Consumer<MinecraftElement> right;
    private Consumer<MinecraftElement> middle;
    private Consumer<MinecraftElement> shiftLeft;
    private Consumer<MinecraftElement> shiftRight;

    public MinecraftElement(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public Item getMaterial() {
        return material;
    }

    public MinecraftElement setMaterial(Item material) {
        this.material = material;
        return this;
    }

    public boolean isEnchanted() {
        return enchanted;
    }

    public MinecraftElement setEnchanted(boolean enchanted) {
        this.enchanted = enchanted;
        return this;
    }

    public String getName() {
        return name;
    }

    public MinecraftElement setName(String name) {
        this.name = name;
        return this;
    }

    public int getCount() {
        return count;
    }

    public MinecraftElement setCount(int count) {
        this.count = count;
        return this;
    }

    public ItemStack getBaseItemStack() {
        return baseItemStack;
    }

    public MinecraftElement setBaseItemStack(ItemStack baseItemStack) {
        this.baseItemStack = baseItemStack;
        return this;
    }

    public List<String> getLore() {
        return lore;
    }

    public MinecraftElement addLore(String loreLine) {
        if (loreLine == null) {
            lore.add(null);
            return this;
        }
        if (loreLine.indexOf('\n') >= 0) {
            for (String line : loreLine.split("\n", -1)) {
                addWrappedLore(line);
            }
            return this;
        }
        addWrappedLore(loreLine);
        return this;
    }

    public MinecraftElement onLeftClick(Consumer<MinecraftElement> clicked) {
        left = clicked;
        return this;
    }

    public MinecraftElement onRightClick(Consumer<MinecraftElement> clicked) {
        right = clicked;
        return this;
    }

    public MinecraftElement onMiddleClick(Consumer<MinecraftElement> clicked) {
        middle = clicked;
        return this;
    }

    public MinecraftElement onShiftLeftClick(Consumer<MinecraftElement> clicked) {
        shiftLeft = clicked;
        return this;
    }

    public MinecraftElement onShiftRightClick(Consumer<MinecraftElement> clicked) {
        shiftRight = clicked;
        return this;
    }

    public ItemStack computeItemStack() {
        if (baseItemStack == null && material == Items.AIR) {
            return ItemStack.EMPTY;
        }
        try {
            ItemStack stack = baseItemStack == null ? new ItemStack(material, count) : baseItemStack.copyWithCount(count);
            if (name == null) {
                stack.remove(DataComponents.CUSTOM_NAME);
            } else {
                stack.set(DataComponents.CUSTOM_NAME, MinecraftLegacyText.component(name));
            }
            if (lore.isEmpty()) {
                stack.remove(DataComponents.LORE);
            } else {
                List<Component> lines = new ArrayList<>(lore.size());
                for (String line : lore) {
                    lines.add(MinecraftLegacyText.component(line));
                }
                stack.set(DataComponents.LORE, new ItemLore(lines));
            }
            if (enchanted) {
                stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            }
            return stack;
        } catch (RuntimeException error) {
            LOGGER.warn("Could not build inventory element {}", id, error);
            return ItemStack.EMPTY;
        }
    }

    void call(MinecraftWindow.Click click) {
        Consumer<MinecraftElement> handler = switch (click) {
            case LEFT -> left;
            case RIGHT -> right;
            case MIDDLE -> middle;
            case SHIFT_LEFT -> shiftLeft;
            case SHIFT_RIGHT -> shiftRight;
        };
        if (handler == null) {
            return;
        }
        try {
            handler.accept(this);
        } catch (RuntimeException error) {
            LOGGER.warn("Inventory element {} failed to handle a {} click", id, click, error);
        }
    }

    private void addWrappedLore(String line) {
        String carry = "";
        String rest = line;
        while (true) {
            String candidate = carry.isEmpty() ? rest : carry + rest;
            int length = candidate.length();
            int visible = 0;
            int breakAt = -1;
            String breakColor = "";
            StringBuilder color = new StringBuilder();
            int index = 0;
            while (index < length) {
                char current = candidate.charAt(index);
                if (current == '\u00A7' && index + 1 < length) {
                    char code = Character.toLowerCase(candidate.charAt(index + 1));
                    if (code == 'x') {
                        int end = Math.min(length, index + 14);
                        color.setLength(0);
                        color.append(candidate, index, end);
                        index = end;
                        continue;
                    }
                    if (code >= '0' && code <= '9' || code >= 'a' && code <= 'f') {
                        color.setLength(0);
                        color.append(current).append(candidate.charAt(index + 1));
                    } else if (code == 'r') {
                        color.setLength(0);
                    } else {
                        color.append(current).append(candidate.charAt(index + 1));
                    }
                    index += 2;
                    continue;
                }
                if (current == ' ' && visible <= LORE_WRAP_VISIBLE_WIDTH && index > 0) {
                    breakAt = index;
                    breakColor = color.toString();
                }
                visible++;
                if (visible > LORE_WRAP_VISIBLE_WIDTH && breakAt >= 0) {
                    break;
                }
                index++;
            }
            if (visible <= LORE_WRAP_VISIBLE_WIDTH || breakAt < 0) {
                lore.add(candidate);
                return;
            }
            lore.add(candidate.substring(0, breakAt));
            carry = breakColor;
            rest = candidate.substring(breakAt + 1);
        }
    }
}
