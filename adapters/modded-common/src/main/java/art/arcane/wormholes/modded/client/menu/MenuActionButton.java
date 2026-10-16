package art.arcane.wormholes.modded.client.menu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Objects;
import java.util.function.Consumer;

public final class MenuActionButton extends AbstractButton {
    private final Component narration;
    private final Consumer<InputWithModifiers> press;

    public MenuActionButton(Component message, Component narration, Consumer<InputWithModifiers> press) {
        super(0, 0, MenuLayout.BTN_H, MenuLayout.BTN_H, MenuComponents.face(message));
        this.narration = Objects.requireNonNull(narration, "narration");
        this.press = Objects.requireNonNull(press, "press");
    }

    @Override
    public void onPress(InputWithModifiers input) {
        press.accept(input);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        extractDefaultSprite(graphics);
        extractScrollingStringOverContents(graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE),
            getMessage(), 2);
    }

    @Override
    protected MutableComponent createNarrationMessage() {
        return wrapDefaultNarrationMessage(narration);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
