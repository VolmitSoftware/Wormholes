package art.arcane.wormholes.menu.transit;

import java.util.UUID;

import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.portal.PortalRoutes;

public final class TransitRoutes {
    public static final String CONTROLLER = "transit";
    public static final String MAIN = "transit.main";

    private TransitRoutes() {
    }

    public static MenuRoute main(UUID portalId) {
        return MenuRoute.of(CONTROLLER, MAIN).with(PortalRoutes.PORTAL, portalId.toString());
    }
}
