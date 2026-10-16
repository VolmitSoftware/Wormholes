package art.arcane.wormholes.menu;

import java.util.Objects;

public record ButtonElement(MenuElementCommon common) implements MenuElement {
    public ButtonElement {
        Objects.requireNonNull(common, "common").requireInteractions(MenuElementRules.PRESSABLE, "button");
    }
}
