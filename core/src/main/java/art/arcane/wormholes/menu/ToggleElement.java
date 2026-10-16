package art.arcane.wormholes.menu;

import java.util.Objects;

public record ToggleElement(MenuElementCommon common, boolean value) implements MenuElement {
    public ToggleElement {
        Objects.requireNonNull(common, "common").requireInteractions(MenuElementRules.PRESSABLE, "toggle");
    }
}
