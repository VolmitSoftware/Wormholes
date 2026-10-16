package art.arcane.wormholes.menu;

import java.util.Objects;

public record LookCaptureElement(MenuElementCommon common) implements MenuElement {
    public LookCaptureElement {
        Objects.requireNonNull(common, "common").requireInteractions(MenuElementRules.PRESSABLE, "look capture");
    }
}
