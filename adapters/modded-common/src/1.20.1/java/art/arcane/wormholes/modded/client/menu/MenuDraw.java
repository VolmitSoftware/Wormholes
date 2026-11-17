package art.arcane.wormholes.modded.client.menu;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class MenuDraw {
    private static final ResourceLocation WIDGETS = new ResourceLocation("textures/gui/widgets.png");
    private static final Map<ResourceLocation, ResourceLocation> TEXTURES = new ConcurrentHashMap<>();

    private MenuDraw() {
    }

    static void sprite(GuiGraphics graphics, ResourceLocation sprite, int x, int y, int width, int height) {
        sprite(graphics, sprite, x, y, width, height, MenuPalette.WHITE);
    }

    static void sprite(GuiGraphics graphics, ResourceLocation sprite, int x, int y, int width, int height, int color) {
        ResourceLocation texture = TEXTURES.computeIfAbsent(sprite,
            id -> new ResourceLocation(id.getNamespace(), "textures/gui/sprites/" + id.getPath() + ".png"));
        graphics.setColor((color >> 16 & 0xFF) / 255.0F, (color >> 8 & 0xFF) / 255.0F, (color & 0xFF) / 255.0F, (color >>> 24) / 255.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.blit(texture, x, y, 0.0F, 0.0F, width, height, width, height);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    static void button(GuiGraphics graphics, AbstractWidget widget) {
        int state = !widget.active ? 0 : widget.isHoveredOrFocused() ? 2 : 1;
        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        graphics.blitNineSliced(WIDGETS, widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight(), 20, 4, 200, 20, 0, 46 + state * 20);
    }

    static void place(AbstractWidget widget, MenuLayout.Rect rect) {
        if (widget == null) {
            return;
        }
        widget.setX(rect.x());
        widget.setY(rect.y());
        widget.setWidth(rect.width());
        if (widget instanceof Sized sized) {
            sized.setHeight(rect.height());
        }
    }

    interface Sized {
        void setHeight(int height);
    }
}
