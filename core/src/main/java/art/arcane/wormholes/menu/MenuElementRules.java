package art.arcane.wormholes.menu;

import java.util.Set;

final class MenuElementRules {
    static final Set<MenuInteraction> PRESSABLE = Set.of(MenuInteraction.PRIMARY, MenuInteraction.SECONDARY, MenuInteraction.TERTIARY);

    private MenuElementRules() {
    }
}
