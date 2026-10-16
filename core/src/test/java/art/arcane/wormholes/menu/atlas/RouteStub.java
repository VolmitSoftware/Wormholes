package art.arcane.wormholes.menu.atlas;

import java.util.ArrayList;
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
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuSlot;

final class RouteStub implements MenuController {
    final List<MenuRoute> routes = new ArrayList<>();
    private final String id;

    RouteStub(String id) {
        this.id = id;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public MenuDecision routeGuard(MenuContext context) {
        return MenuDecision.allow();
    }

    @Override
    public MenuBuild build(MenuContext context) {
        routes.add(context.route());
        MenuScreen screen = MenuScreen.builder(context.route().screen())
            .elements(List.of(new LabelElement(MenuElementCommon.builder("stub").chestSlot(new MenuSlot(0, 0)).build())))
            .build();
        return new MenuBuild(screen, new ChestLayout("Stub", 1, ChestPane.GRAY, null), MenuBindings.builder().build());
    }
}
