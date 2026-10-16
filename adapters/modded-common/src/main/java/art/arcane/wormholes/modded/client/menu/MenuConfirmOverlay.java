package art.arcane.wormholes.modded.client.menu;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class MenuConfirmOverlay {
    public static final int ACTIVATION_TICKS = 20;

    private final List<Component> message;
    private final MenuActionButton confirm;
    private final MenuActionButton cancel;
    private List<FormattedCharSequence> lines = List.of();
    private MenuLayout.Confirm layout;
    private int age;

    public MenuConfirmOverlay(List<Component> message, Component confirmLabel, Component cancelLabel, Runnable onConfirm,
                              Runnable onCancel) {
        this.message = List.copyOf(message);
        Objects.requireNonNull(onConfirm, "onConfirm");
        Objects.requireNonNull(onCancel, "onCancel");
        this.confirm = new MenuActionButton(confirmLabel, confirmLabel, input -> onConfirm.run());
        this.cancel = new MenuActionButton(cancelLabel, cancelLabel, input -> onCancel.run());
        refreshActivation();
    }

    public MenuActionButton confirmButton() {
        return confirm;
    }

    public MenuActionButton cancelButton() {
        return cancel;
    }

    public List<AbstractWidget> widgets() {
        return List.of(confirm, cancel);
    }

    public boolean armed() {
        return age >= ACTIVATION_TICKS;
    }

    public List<Component> message() {
        return message;
    }

    public boolean tick() {
        if (age >= ACTIVATION_TICKS) {
            return false;
        }
        age++;
        refreshActivation();
        return armed();
    }

    public void layout(Font font, int screenWidth, int screenHeight) {
        int wrap = Math.max(1, Math.min(screenWidth - MenuLayout.CONFIRM_WRAP_INSET, MenuLayout.CONFIRM_WRAP_W));
        List<FormattedCharSequence> wrapped = new ArrayList<>();
        for (Component line : message) {
            wrapped.addAll(font.split(line, wrap));
        }
        lines = wrapped.size() > MenuLayout.CONFIRM_MAX_LINES ? List.copyOf(wrapped.subList(0, MenuLayout.CONFIRM_MAX_LINES)) : List.copyOf(wrapped);
        layout = MenuLayout.confirm(screenWidth, screenHeight, lines.size());
        place(confirm, layout.confirm());
        place(cancel, layout.cancel());
    }

    public void extract(GuiGraphicsExtractor graphics, Font font, int screenWidth, int screenHeight, int mouseX, int mouseY,
                        float partialTick) {
        if (layout == null) {
            return;
        }
        graphics.fill(0, 0, screenWidth, screenHeight, MenuPalette.DIM);
        MenuLayout.Rect panel = layout.panel();
        graphics.fill(panel.x(), panel.y(), panel.right(), panel.bottom(), MenuPalette.PANEL);
        graphics.outline(panel.x(), panel.y(), panel.width(), panel.height(), MenuPalette.PANEL_EDGE);
        int centerX = screenWidth / 2;
        for (int index = 0; index < lines.size(); index++) {
            FormattedCharSequence line = lines.get(index);
            graphics.text(font, line, centerX - font.width(line) / 2, layout.textTop() + index * MenuLayout.LINE_H, MenuPalette.WHITE, true);
        }
        confirm.extractRenderState(graphics, mouseX, mouseY, partialTick);
        cancel.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void refreshActivation() {
        boolean armed = armed();
        confirm.active = armed;
        cancel.active = armed;
    }

    private static void place(AbstractWidget widget, MenuLayout.Rect rect) {
        widget.setRectangle(rect.width(), rect.height(), rect.x(), rect.y());
    }
}
