package art.arcane.wormholes.menu.destination;

import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.portal.PortalRoutes;

public final class DestinationRoutes {
    public static final String SELECT = "destination.select";

    private DestinationRoutes() {
    }

    public static MenuRoute select() {
        return MenuRoute.of(PortalRoutes.DESTINATION_CONTROLLER, SELECT);
    }
}
