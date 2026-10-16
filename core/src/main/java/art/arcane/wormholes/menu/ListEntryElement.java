package art.arcane.wormholes.menu;

import java.util.List;
import java.util.Objects;

public record ListEntryElement(MenuElementCommon common, boolean selected) implements MenuElement {
    public ListEntryElement {
        Objects.requireNonNull(common, "common").requireInteractions(MenuElementRules.PRESSABLE, "list entry");
    }

    public List<MenuSideAction> secondaryActions() {
        return common.sideActions();
    }
}
