package art.arcane.wormholes.modded.client.menu;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.Objects;
import java.util.function.Consumer;

public final class MenuTextField extends EditBox {
    private final Consumer<String> submit;
    private boolean editable = true;

    public MenuTextField(Font font, Component narration, Consumer<String> submit) {
        super(font, 0, 0, MenuLayout.TEXT_MIN_W, MenuLayout.BTN_H, narration);
        this.submit = Objects.requireNonNull(submit, "submit");
    }

    @Override
    public void setEditable(boolean editable) {
        super.setEditable(editable);
        this.editable = editable;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (isActive() && editable && event.isConfirmation()) {
            submit.accept(getValue());
            return true;
        }
        return super.keyPressed(event);
    }
}
