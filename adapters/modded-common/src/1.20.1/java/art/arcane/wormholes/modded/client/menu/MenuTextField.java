package art.arcane.wormholes.modded.client.menu;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.Objects;
import java.util.function.Consumer;

public final class MenuTextField extends EditBox implements MenuDraw.Sized {
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
    public void setHeight(int height) {
        this.height = height;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (isActive() && editable && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            submit.accept(getValue());
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
