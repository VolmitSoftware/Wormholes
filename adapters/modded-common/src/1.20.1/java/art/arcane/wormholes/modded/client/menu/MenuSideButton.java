package art.arcane.wormholes.modded.client.menu;

import art.arcane.wormholes.menu.MenuIcon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.Objects;
import java.util.function.Consumer;

public final class MenuSideButton extends AbstractButton implements MenuDraw.Sized {
    private final MenuIconRenderer icon;
    private final Consumer<MenuInput> press;

    public MenuSideButton(Component label, MenuIcon icon, Consumer<MenuInput> press) {
        super(0, 0, MenuLayout.SIDE_BUTTON, MenuLayout.BTN_H, label);
        this.icon = MenuIconRenderer.of(icon);
        this.press = Objects.requireNonNull(press, "press");
        if (!label.getString().isEmpty()) {
            setTooltip(Tooltip.create(label));
        }
    }

    @Override
    public void onPress() {
        press.accept(MenuInput.current());
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        MenuDraw.button(graphics, this);
        icon.draw(graphics, Minecraft.getInstance().font, getX() + (getWidth() - MenuLayout.ICON) / 2,
            getY() + (getHeight() - MenuLayout.ICON) / 2, active);
    }

    @Override
    public void setHeight(int height) {
        this.height = height;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
