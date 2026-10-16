package art.arcane.wormholes.menu;

import java.util.Objects;

public record ChestTarget(String elementId, MenuInteraction interaction, boolean preconfirmed) {
    public ChestTarget {
        if (elementId != null) {
            MenuText.id(elementId, "chest target element");
        }
        Objects.requireNonNull(interaction, "interaction");
    }

    public static ChestTarget self(MenuInteraction interaction) {
        return new ChestTarget(null, interaction, false);
    }

    public static ChestTarget preconfirmed(MenuInteraction interaction) {
        return new ChestTarget(null, interaction, true);
    }

    public static ChestTarget delegate(String elementId, MenuInteraction interaction) {
        return new ChestTarget(elementId, interaction, false);
    }
}
