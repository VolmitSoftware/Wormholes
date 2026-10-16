package art.arcane.wormholes.menu;

import java.util.Objects;

public record MenuAction(long revision, String elementId, MenuInteraction interaction, MenuValue value) {
    public MenuAction {
        elementId = MenuText.id(elementId, "element id");
        Objects.requireNonNull(interaction, "interaction");
    }

    public static MenuAction of(long revision, String elementId, MenuInteraction interaction) {
        return new MenuAction(revision, elementId, interaction, null);
    }
}
