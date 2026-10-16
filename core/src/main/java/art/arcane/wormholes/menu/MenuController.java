package art.arcane.wormholes.menu;

public interface MenuController {
    String id();

    MenuDecision routeGuard(MenuContext context);

    MenuBuild build(MenuContext context);

    default void entered(MenuContext context) {
    }

    default int initialPage(MenuContext context) {
        return 0;
    }

    default int refreshIntervalTicks(MenuRoute route) {
        return 0;
    }

    default boolean touches(MenuRoute route, MenuScope scope) {
        return false;
    }
}
