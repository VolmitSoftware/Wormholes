package art.arcane.wormholes.menu;

import java.util.List;
import java.util.Set;

public sealed interface MenuElement permits ButtonElement, ToggleElement, CycleElement, NumberElement, TextElement,
    ListEntryElement, LabelElement, ItemCaptureElement, LookCaptureElement {
    MenuElementCommon common();

    default String id() {
        return common().id();
    }

    default String label() {
        return common().label();
    }

    default List<String> description() {
        return common().description();
    }

    default MenuIcon icon() {
        return common().icon();
    }

    default boolean enabled() {
        return common().enabled();
    }

    default MenuStyle style() {
        return common().style();
    }

    default Set<MenuInteraction> interactions() {
        return common().interactions();
    }

    default MenuSlot chestSlot() {
        return common().chestSlot();
    }

    default boolean accepts(MenuInteraction interaction) {
        return common().interactions().contains(interaction);
    }
}
