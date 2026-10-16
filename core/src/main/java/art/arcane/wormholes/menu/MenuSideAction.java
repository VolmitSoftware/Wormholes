package art.arcane.wormholes.menu;

import java.util.Objects;
import java.util.Set;

public record MenuSideAction(MenuInteraction interaction, String label, MenuIcon icon) {
    private static final Set<MenuInteraction> ALLOWED = Set.of(MenuInteraction.PRIMARY, MenuInteraction.SECONDARY, MenuInteraction.TERTIARY);

    public MenuSideAction {
        Objects.requireNonNull(interaction, "interaction");
        if (!ALLOWED.contains(interaction)) {
            throw new IllegalArgumentException("side action " + interaction + " must be PRIMARY, SECONDARY, or TERTIARY");
        }
        label = MenuText.text(label, "side action label");
        Objects.requireNonNull(icon, "icon");
    }
}
