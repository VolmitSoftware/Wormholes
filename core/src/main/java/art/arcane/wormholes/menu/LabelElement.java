package art.arcane.wormholes.menu;

import java.util.Objects;
import java.util.Set;

public record LabelElement(MenuElementCommon common) implements MenuElement {
    public LabelElement {
        Objects.requireNonNull(common, "common").requireInteractions(Set.of(), "label");
    }
}
