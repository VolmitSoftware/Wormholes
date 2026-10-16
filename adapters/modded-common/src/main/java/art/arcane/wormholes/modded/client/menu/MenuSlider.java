package art.arcane.wormholes.modded.client.menu;

import art.arcane.wormholes.menu.NumberElement;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Objects;
import java.util.function.DoubleConsumer;

public final class MenuSlider extends AbstractSliderButton {
    private final NumberElement element;
    private final Component label;
    private final Component display;
    private final DoubleConsumer submit;
    private double current;

    public MenuSlider(NumberElement element, Component label, DoubleConsumer submit) {
        super(0, 0, MenuLayout.SLIDER_W, MenuLayout.BTN_H, Component.empty(),
            MenuNumbers.fraction(element.value(), element.min(), element.max()));
        this.element = element;
        this.label = Objects.requireNonNull(label, "label");
        this.display = MenuComponents.face(MenuComponents.component(element.display()));
        this.submit = Objects.requireNonNull(submit, "submit");
        this.current = element.value();
        updateMessage();
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        super.onRelease(event);
        if (current != element.value()) {
            submit.accept(current);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!isActive()) {
            return false;
        }
        if (event.isSelection()) {
            if (current != element.value()) {
                submit.accept(current);
            }
            return true;
        }
        if (event.isLeft() || event.isRight()) {
            current = MenuNumbers.stepped(current, event.isLeft() ? -1 : 1, element.min(), element.max(), element.step());
            value = MenuNumbers.fraction(current, element.min(), element.max());
            updateMessage();
            return true;
        }
        return false;
    }

    @Override
    protected void updateMessage() {
        setMessage(current == element.value() && !element.display().isEmpty() ? display : Component.literal(MenuNumbers.format(current)));
    }

    @Override
    protected void applyValue() {
        current = MenuNumbers.valueAt(value, element.min(), element.max(), element.step());
    }

    @Override
    protected MutableComponent createNarrationMessage() {
        return Component.translatable("gui.narrate.slider", Component.empty().append(label).append(", ").append(getMessage()));
    }
}
