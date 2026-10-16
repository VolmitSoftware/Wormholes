package art.arcane.wormholes.modded.client.menu;

import art.arcane.wormholes.menu.MenuIcon;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Objects;

public final class MenuIconRenderer {
    private static final String NAMESPACE = "wormholes";
    private static final String SPRITE_ROOT = "icon/";
    private static final String SMALL_PREFIX = "small/";

    private final MenuIcon icon;
    private final Identifier sprite;
    private final ItemStack stack;

    private MenuIconRenderer(MenuIcon icon, Identifier sprite, ItemStack stack) {
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

    public static Identifier sprite(String name) {
        return Identifier.fromNamespaceAndPath(NAMESPACE, SPRITE_ROOT + name);
    }

    public static ItemStack stack(MenuIcon.Item item) {
        Identifier id = Identifier.tryParse(item.itemId());
        Item resolved = id != null && BuiltInRegistries.ITEM.containsKey(id) ? BuiltInRegistries.ITEM.getValue(id) : Items.BARRIER;
        ItemStack created = new ItemStack(resolved, item.count());
        if (item.glint()) {
            created.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return created;
    }

    public boolean small() {
        return icon instanceof MenuIcon.Sprite named && named.name().startsWith(SMALL_PREFIX);
    }

    public void draw(GuiGraphicsExtractor graphics, Font font, int x, int y, boolean enabled) {
        if (sprite != null) {
            int size = small() ? MenuLayout.SMALL_ICON : MenuLayout.ICON;
            int offset = (MenuLayout.ICON - size) / 2;
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x + offset, y + offset, size, size,
                enabled ? MenuPalette.WHITE : MenuPalette.ICON_DISABLED);
            return;
        }
        graphics.item(stack, x, y);
        if (stack.getCount() > 1) {
            graphics.itemDecorations(font, stack, x, y);
        }
        if (!enabled) {
            graphics.fill(x, y, x + MenuLayout.ICON, y + MenuLayout.ICON, MenuPalette.ITEM_DISABLED);
        }
    }
}
