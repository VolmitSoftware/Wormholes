package art.arcane.wormholes.menu.cosmetics;

import java.util.UUID;

import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.portal.PortalRoutes;

public final class CosmeticsRoutes {
    public static final String CONTROLLER = "cosmetics";
    public static final String BLACKOUT = "cosmetics.blackout";
    public static final String AMBIENT = "cosmetics.ambient";
    public static final String SKIN = "cosmetics.skin";

    private CosmeticsRoutes() {
    }

    public static MenuRoute blackout(UUID portalId) {
        return route(BLACKOUT, portalId);
    }

    public static MenuRoute ambient(UUID portalId) {
        return route(AMBIENT, portalId);
    }

    public static MenuRoute skin(UUID portalId) {
        return route(SKIN, portalId);
    }

    private static MenuRoute route(String screen, UUID portalId) {
        return MenuRoute.of(CONTROLLER, screen).with(PortalRoutes.PORTAL, portalId.toString());
    }
}
