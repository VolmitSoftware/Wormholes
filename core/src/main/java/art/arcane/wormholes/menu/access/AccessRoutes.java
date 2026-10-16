package art.arcane.wormholes.menu.access;

import java.util.UUID;

import art.arcane.wormholes.menu.MenuRoute;

public final class AccessRoutes {
    public static final String CONTROLLER = "access";
    public static final String ROLES = "access.roles";
    public static final String PORTAL = "portal";

    private AccessRoutes() {
    }

    public static MenuRoute roles(UUID portalId) {
        return MenuRoute.of(CONTROLLER, ROLES).with(PORTAL, portalId.toString());
    }

    public static UUID portalId(MenuRoute route) {
        return route.uuid(PORTAL);
    }
}
