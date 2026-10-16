package art.arcane.wormholes.menu.portal;

import java.util.List;

import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.LabelElement;
import art.arcane.wormholes.menu.MenuBindings;
import art.arcane.wormholes.menu.MenuBuild;
import art.arcane.wormholes.menu.MenuContext;
import art.arcane.wormholes.menu.MenuController;
import art.arcane.wormholes.menu.MenuDecision;
import art.arcane.wormholes.menu.MenuElementCommon;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuSlot;

public record StubController(String id) implements MenuController {
    @Override
    public MenuDecision routeGuard(MenuContext context) {
        return MenuDecision.allow();
    }

    @Override
    public MenuBuild build(MenuContext context) {
        MenuScreen screen = MenuScreen.builder(context.route().screen())
            .elements(List.of(new LabelElement(MenuElementCommon.builder("stub").chestSlot(new MenuSlot(0, 0)).build())))
            .build();
        return new MenuBuild(screen, new ChestLayout("Stub", 1, ChestPane.GRAY, null), MenuBindings.builder().build());
    }
}
