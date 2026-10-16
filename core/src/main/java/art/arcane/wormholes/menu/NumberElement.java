package art.arcane.wormholes.menu;

import java.util.Objects;
import java.util.Set;

public record NumberElement(
    MenuElementCommon common,
    double value,
    double min,
    double max,
    double step,
    double bigStep,
    String display
) implements MenuElement {
    private static final Set<MenuInteraction> ALLOWED = Set.of(MenuInteraction.INCREMENT, MenuInteraction.DECREMENT,
        MenuInteraction.BIG_INCREMENT, MenuInteraction.BIG_DECREMENT, MenuInteraction.SET_VALUE);

    public NumberElement {
        Objects.requireNonNull(common, "common").requireInteractions(ALLOWED, "number");
        requireFinite(value, "value");
        requireFinite(min, "min");
        requireFinite(max, "max");
        requireFinite(step, "step");
        requireFinite(bigStep, "big step");
        if (min > max) {
            throw new IllegalArgumentException("number min " + min + " exceeds max " + max);
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException("number value " + value + " is outside " + min + ".." + max);
        }
        if (step <= 0.0D || bigStep <= 0.0D) {
            throw new IllegalArgumentException("number steps must be positive");
        }
        display = MenuText.text(display, "display");
    }

    public boolean containsValue(double candidate) {
        return Double.isFinite(candidate) && candidate >= min && candidate <= max;
    }

    private static void requireFinite(double candidate, String field) {
        if (!Double.isFinite(candidate)) {
            throw new IllegalArgumentException("number " + field + " must be finite");
        }
    }
}
