package art.arcane.wormholes.menu;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record CycleElement(MenuElementCommon common, List<String> options, int index) implements MenuElement {
    private static final Set<MenuInteraction> ALLOWED = Set.of(MenuInteraction.PRIMARY, MenuInteraction.SECONDARY,
        MenuInteraction.TERTIARY, MenuInteraction.SELECT);

    public CycleElement {
        Objects.requireNonNull(common, "common").requireInteractions(ALLOWED, "cycle");
        options = MenuText.lines(options, MenuLimits.MAX_CYCLE_OPTIONS, "cycle options");
        if (options.isEmpty()) {
            throw new IllegalArgumentException("cycle element " + common.id() + " needs at least one option");
        }
        if (index < 0 || index >= options.size()) {
            throw new IllegalArgumentException("cycle index " + index + " is outside 0.." + (options.size() - 1));
        }
    }

    public boolean containsIndex(int candidate) {
        return candidate >= 0 && candidate < options.size();
    }
}
