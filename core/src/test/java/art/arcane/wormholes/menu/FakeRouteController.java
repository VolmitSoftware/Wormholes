package art.arcane.wormholes.menu;

import java.util.List;

public record FakeRouteController(String id) implements MenuController {
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
