package art.arcane.wormholes.modded.client.menu;

import art.arcane.wormholes.menu.MenuFeedback;
import art.arcane.wormholes.menu.MenuFeedbackStyle;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Optional;

public final class MenuFeedbackLine {
    public static final long LIFETIME_MILLIS = 5000L;
    private static final long DOT_PERIOD_MILLIS = 300L;
    private static final String[] DOTS = {".", "..", "..."};
    private static final Identifier SUCCESS = MenuIconRenderer.sprite("small/confirm");
    private static final Identifier ERROR = MenuIconRenderer.sprite("small/cancel");
    private static final Identifier WARNING = MenuIconRenderer.sprite("small/warning");

    private MenuFeedback feedback;
    private MenuClippedText text;
    private long shownAt;
    private boolean pending;
    private long pendingSince;

    public void show(MenuFeedback next, long now) {
        feedback = next;
        text = new MenuClippedText(MenuComponents.component(next.text()));
        shownAt = now;
        pending = false;
    }

    public void pending(long now) {
        pending = true;
        pendingSince = now;
        feedback = null;
        text = null;
    }

    public boolean answer(MenuFeedback next, MenuFeedback previous, long now) {
        boolean answered = pending;
        pending = false;
        if (next == null || !answered && next.equals(previous)) {
            return false;
        }
        show(next, now);
        return true;
    }

    public void settle() {
        pending = false;
    }

    public boolean pending() {
        return pending;
    }

    public boolean timedOut(long now) {
        return pending && now - pendingSince >= LIFETIME_MILLIS;
    }

    public boolean live(long now) {
        return pending || feedback != null && now - shownAt < LIFETIME_MILLIS;
    }

    public Optional<Component> message() {
        return text == null ? Optional.empty() : Optional.of(text.text());
    }

    public void extract(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, long now) {
        if (!live(now)) {
            return;
        }
        graphics.fill(x, y, x + width, y + MenuLayout.FEEDBACK_H, MenuPalette.PLATE);
        int textY = y + (MenuLayout.FEEDBACK_H - 8) / 2;
        if (pending) {
            String shown = DOTS[(int) (((now - pendingSince) / DOT_PERIOD_MILLIS) % DOTS.length)];
            graphics.text(font, shown, x + width / 2 - font.width(DOTS[DOTS.length - 1]) / 2, textY, MenuPalette.TEXT, true);
            return;
        }
        Identifier icon = icon(feedback.style());
        int textX = x + MenuLayout.PAD;
        if (icon != null) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, icon, textX, y, MenuLayout.SMALL_ICON, MenuLayout.SMALL_ICON);
            textX += MenuLayout.SMALL_ICON + MenuLayout.GAP_S;
        }
        graphics.text(font, text.clip(font, x + width - MenuLayout.PAD - textX), textX, textY, color(feedback.style()), true);
    }

    private static Identifier icon(MenuFeedbackStyle style) {
        return switch (style) {
            case SUCCESS -> SUCCESS;
            case ERROR -> ERROR;
            case WARNING -> WARNING;
            case INFO -> null;
        };
    }

    private static int color(MenuFeedbackStyle style) {
        return switch (style) {
            case SUCCESS -> MenuPalette.SUCCESS;
            case ERROR -> MenuPalette.ERROR;
            case WARNING -> MenuPalette.WARNING;
            case INFO -> MenuPalette.TEXT;
        };
    }
}
