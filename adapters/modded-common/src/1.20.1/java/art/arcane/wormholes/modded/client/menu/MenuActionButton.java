package art.arcane.wormholes.modded.client.menu;

import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Objects;
import java.util.function.Consumer;

public final class MenuActionButton extends AbstractButton implements MenuDraw.Sized {
    private final Component narration;
    private final Consumer<MenuInput> press;

    public MenuActionButton(Component message, Component narration, Consumer<MenuInput> press) {
        super(0, 0, MenuLayout.BTN_H, MenuLayout.BTN_H, MenuComponents.face(message));
        this.narration = Objects.requireNonNull(narration, "narration");
        this.press = Objects.requireNonNull(press, "press");
    }

    @Override
    public void onPress() {
        press.accept(MenuInput.current());
    }

    @Override
    public void setHeight(int height) {
        this.height = height;
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
