package art.arcane.wormholes.modded.client.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.CommonInputs;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public final class MenuEntryWidget extends AbstractWidget implements MenuDraw.Sized {
    private static final ResourceLocation SELECTED = MenuIconRenderer.sprite("small/confirm");
    private static final int LABEL_INSET = MenuLayout.PAD + MenuLayout.ICON + MenuLayout.GAP_S;

    private final MenuIconRenderer icon;
    private final MenuClippedText label;
    private final MenuClippedText secondary;
    private final boolean selected;
    private final boolean enabled;
    private final Component narration;
    private final Runnable press;

    public MenuEntryWidget(Component label, Component secondary, boolean selected, boolean enabled, MenuIconRenderer icon,
                           Component narration, Runnable press) {
        super(0, 0, MenuLayout.BTN_H, MenuLayout.BTN_H, label);
        this.icon = Objects.requireNonNull(icon, "icon");
        this.label = new MenuClippedText(label);
        this.secondary = secondary == null ? null : new MenuClippedText(secondary);
        this.selected = selected;
        this.enabled = enabled;
        this.narration = Objects.requireNonNull(narration, "narration");
        this.press = Objects.requireNonNull(press, "press");
    }

    public boolean truncated() {
        Font font = Minecraft.getInstance().font;
        return label.truncated(font, textWidth()) || secondary != null && secondary.truncated(font, textWidth());
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        press.run();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!isActive() || !CommonInputs.selected(keyCode)) {
            return false;
        }
        playDownSound(Minecraft.getInstance().getSoundManager());
        press.run();
        return true;
    }

    @Override
    public void setHeight(int height) {
        this.height = height;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        if (isHovered() && isActive()) {
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), MenuPalette.WASH);
        }
        if (isFocused()) {
            graphics.renderOutline(getX(), getY(), getWidth(), getHeight(), MenuPalette.WHITE);
        }
        icon.draw(graphics, font, getX() + MenuLayout.PAD, getY() + (getHeight() - MenuLayout.ICON) / 2, enabled);
        int labelColor = !enabled ? MenuPalette.DISABLED : selected ? MenuPalette.ACCENT_TEXT : MenuPalette.WHITE;
        int textX = getX() + LABEL_INSET;
        if (secondary == null) {
            graphics.drawString(font, label.clip(font, textWidth()), textX, getY() + (getHeight() - 8) / 2, labelColor, true);
        } else {
            graphics.drawString(font, label.clip(font, textWidth()), textX, getY() + 3, labelColor, true);
            graphics.drawString(font, secondary.clip(font, textWidth()), textX, getY() + 14,
                enabled ? MenuPalette.SECONDARY : MenuPalette.DISABLED, true);
        }
        if (selected) {
            MenuDraw.sprite(graphics, SELECTED, getX() + getWidth() - MenuLayout.PAD - MenuLayout.SMALL_ICON,
                getY() + (getHeight() - MenuLayout.SMALL_ICON) / 2, MenuLayout.SMALL_ICON, MenuLayout.SMALL_ICON);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, narration);
    }

    private int textWidth() {
        int markerWidth = selected ? MenuLayout.SMALL_ICON + MenuLayout.GAP_S : 0;
        return getWidth() - LABEL_INSET - MenuLayout.PAD - markerWidth;
    }
}
