package art.arcane.wormholes.menu;

import java.util.Objects;

public record MenuChestAction(String elementId, MenuInteraction interaction, MenuValue value, boolean preconfirmed,
                              CapturedItem capturedItem) {
    public MenuChestAction {
        elementId = MenuText.id(elementId, "element id");
        Objects.requireNonNull(interaction, "interaction");
    }
}
