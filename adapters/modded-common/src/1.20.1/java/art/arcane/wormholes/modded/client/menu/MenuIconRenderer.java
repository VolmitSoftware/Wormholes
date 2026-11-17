package art.arcane.wormholes.modded.client.menu;

import art.arcane.wormholes.menu.MenuIcon;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Objects;

public final class MenuIconRenderer {
    private static final String NAMESPACE = "wormholes";
    private static final String SPRITE_ROOT = "icon/";
    private static final String SMALL_PREFIX = "small/";

    private final MenuIcon icon;
    private final ResourceLocation sprite;
    private final ItemStack stack;

    private MenuIconRenderer(MenuIcon icon, ResourceLocation sprite, ItemStack stack) {
        this.icon = icon;
        this.sprite = sprite;
        this.stack = stack;
    }

    public static MenuIconRenderer of(MenuIcon icon) {
        MenuIcon present = Objects.requireNonNull(icon, "icon");
        return switch (present) {
            case MenuIcon.Sprite sprite -> new MenuIconRenderer(present, sprite(sprite.name()), ItemStack.EMPTY);
            case MenuIcon.Item item -> new MenuIconRenderer(present, null, stack(item));
        };
    }

    public static ResourceLocation sprite(String name) {
        return new ResourceLocation(NAMESPACE, SPRITE_ROOT + name);
    }

    public static ItemStack stack(MenuIcon.Item item) {
        ResourceLocation id = ResourceLocation.tryParse(item.itemId());
        Item resolved = id != null && BuiltInRegistries.ITEM.containsKey(id) ? BuiltInRegistries.ITEM.get(id) : Items.BARRIER;
        ItemStack created = new ItemStack(resolved, item.count());
        if (item.glint()) {
            ListTag enchantments = new ListTag();
            enchantments.add(new CompoundTag());
            created.getOrCreateTag().put(ItemStack.TAG_ENCH, enchantments);
        }
        return created;
    }

    public boolean small() {
        return icon instanceof MenuIcon.Sprite named && named.name().startsWith(SMALL_PREFIX);
    }

    public void draw(GuiGraphics graphics, Font font, int x, int y, boolean enabled) {
        if (sprite != null) {
            int size = small() ? MenuLayout.SMALL_ICON : MenuLayout.ICON;
            int offset = (MenuLayout.ICON - size) / 2;
            MenuDraw.sprite(graphics, sprite, x + offset, y + offset, size, size, enabled ? MenuPalette.WHITE : MenuPalette.ICON_DISABLED);
            return;
        }
        graphics.renderItem(stack, x, y);
        if (stack.getCount() > 1) {
            graphics.renderItemDecorations(font, stack, x, y);
        }
        if (!enabled) {
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, 200.0F);
            graphics.fill(x, y, x + MenuLayout.ICON, y + MenuLayout.ICON, MenuPalette.ITEM_DISABLED);
            graphics.pose().popPose();
        }
    }
}
