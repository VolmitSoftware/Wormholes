package art.arcane.wormholes.menu.fidelity;

import java.util.UUID;

import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.portal.PortalRoutes;

public final class FidelityRoutes {
    public static final String CONTROLLER = "fidelity";
    public static final String MAIN = "fidelity.main";

    private FidelityRoutes() {
    }

    public static MenuRoute main(UUID portalId) {
        return MenuRoute.of(CONTROLLER, MAIN).with(PortalRoutes.PORTAL, portalId.toString());
    }
}
