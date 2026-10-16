package art.arcane.wormholes.modded.client.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Objects;
import java.util.function.Consumer;

public final class MenuRowButton extends AbstractButton {
    private static final int LABEL_INSET = MenuLayout.PAD + MenuLayout.ICON + MenuLayout.GAP_S;

    private final MenuIconRenderer icon;
    private final MenuClippedText label;
    private final Component narration;
    private final Consumer<InputWithModifiers> press;

    public MenuRowButton(Component label, Component narration, MenuIconRenderer icon, Consumer<InputWithModifiers> press) {
        super(0, 0, MenuLayout.BTN_H, MenuLayout.BTN_H, MenuComponents.face(label));
        this.icon = Objects.requireNonNull(icon, "icon");
        this.label = new MenuClippedText(getMessage());
        this.narration = Objects.requireNonNull(narration, "narration");
        this.press = Objects.requireNonNull(press, "press");
    }

    public boolean truncated() {
        return label.truncated(Minecraft.getInstance().font, labelWidth());
    }

    @Override
    public void onPress(InputWithModifiers input) {
        press.accept(input);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        extractDefaultSprite(graphics);
        icon.draw(graphics, font, getX() + MenuLayout.PAD, getY() + (getHeight() - MenuLayout.ICON) / 2, active);
        graphics.text(font, label.clip(font, labelWidth()), getX() + LABEL_INSET, getY() + (getHeight() - 8) / 2,
            active ? MenuPalette.WHITE : MenuPalette.SECONDARY, true);
    }

    @Override
    protected MutableComponent createNarrationMessage() {
        return wrapDefaultNarrationMessage(narration);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }

    private int labelWidth() {
        return getWidth() - LABEL_INSET - MenuLayout.GAP_S;
    }
}
