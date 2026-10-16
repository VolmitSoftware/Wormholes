package art.arcane.wormholes.menu.nexus;

import java.util.UUID;

import art.arcane.wormholes.menu.MenuRoute;

public final class NexusRoutes {
    public static final String NETWORK_CONTROLLER = "nexus.network";
    public static final String DIAL_CONTROLLER = "nexus.dial";
    public static final String NETWORK = "nexus.network";
    public static final String DIAL = "nexus.dial";
    public static final String PORTAL = "portal";

    private NexusRoutes() {
    }

    public static MenuRoute network(UUID portalId) {
        return MenuRoute.of(NETWORK_CONTROLLER, NETWORK).with(PORTAL, portalId.toString());
    }

    public static MenuRoute dial(UUID portalId) {
        return MenuRoute.of(DIAL_CONTROLLER, DIAL).with(PORTAL, portalId.toString());
    }

    public static UUID portalId(MenuRoute route) {
        return route.uuid(PORTAL);
    }
}
