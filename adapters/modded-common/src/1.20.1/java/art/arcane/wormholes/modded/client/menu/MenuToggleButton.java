package art.arcane.wormholes.modded.client.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.function.Consumer;

public final class MenuToggleButton extends AbstractButton implements MenuDraw.Sized {
    private static final ResourceLocation ON = MenuIconRenderer.sprite("toggle_on");
    private static final ResourceLocation OFF = MenuIconRenderer.sprite("toggle_off");

    private final boolean value;
    private final Component label;
    private final Consumer<MenuInput> press;

    public MenuToggleButton(Component label, boolean value, Consumer<MenuInput> press) {
        super(0, 0, MenuLayout.TOGGLE_W, MenuLayout.BTN_H, word(value));
        this.value = value;
        this.label = Objects.requireNonNull(label, "label");
        this.press = Objects.requireNonNull(press, "press");
    }

    public static Component word(boolean value) {
        return value ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF;
    }

    @Override
    public void onPress() {
        press.accept(MenuInput.current());
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        MenuDraw.button(graphics, this);
        int iconX = getX() + MenuLayout.GAP_S;
        int iconY = getY() + (getHeight() - MenuLayout.ICON) / 2;
        MenuDraw.sprite(graphics, value ? ON : OFF, iconX, iconY, MenuLayout.ICON, MenuLayout.ICON,
            active ? MenuPalette.WHITE : MenuPalette.ICON_DISABLED);
        Component text = word(value);
        int textLeft = iconX + MenuLayout.ICON + MenuLayout.GAP_S;
        int textWidth = font.width(text);
        int textX = textLeft + Math.max(0, (getX() + getWidth() - MenuLayout.GAP_S - textLeft - textWidth) / 2);
        graphics.drawString(font, text, textX, getY() + (getHeight() - 8) / 2, active ? MenuPalette.WHITE : MenuPalette.SECONDARY, true);
    }

    @Override
    public void setHeight(int height) {
        this.height = height;
    }

    @Override
    protected MutableComponent createNarrationMessage() {
        return wrapDefaultNarrationMessage(Component.empty().append(label).append(", ").append(word(value)));
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
