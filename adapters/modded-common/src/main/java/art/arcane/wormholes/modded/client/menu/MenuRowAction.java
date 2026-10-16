package art.arcane.wormholes.modded.client.menu;

import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuValue;

import java.util.Objects;

public record MenuRowAction(String elementId, MenuInteraction interaction, MenuValue value, MenuRow.Part part) {
    public MenuRowAction {
        Objects.requireNonNull(elementId, "elementId");
        Objects.requireNonNull(interaction, "interaction");
        Objects.requireNonNull(part, "part");
    }
}
