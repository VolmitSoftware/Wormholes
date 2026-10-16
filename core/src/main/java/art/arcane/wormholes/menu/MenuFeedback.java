package art.arcane.wormholes.menu;

import java.util.Objects;

public record MenuFeedback(MenuFeedbackStyle style, String text) {
    public MenuFeedback {
        Objects.requireNonNull(style, "style");
        text = MenuText.text(text, "feedback");
    }

    public static MenuFeedback info(String text) {
        return new MenuFeedback(MenuFeedbackStyle.INFO, text);
    }

    public static MenuFeedback success(String text) {
        return new MenuFeedback(MenuFeedbackStyle.SUCCESS, text);
    }

    public static MenuFeedback error(String text) {
        return new MenuFeedback(MenuFeedbackStyle.ERROR, text);
    }
}
