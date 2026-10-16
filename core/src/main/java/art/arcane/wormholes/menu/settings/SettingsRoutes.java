package art.arcane.wormholes.menu.settings;

import java.util.UUID;

import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.portal.PortalRoutes;

public final class SettingsRoutes {
    public static final String ADVANCED = "settings.advanced";

    private SettingsRoutes() {
    }

    public static MenuRoute advanced(UUID portalId) {
        return MenuRoute.of(PortalRoutes.SETTINGS_CONTROLLER, ADVANCED).with(PortalRoutes.PORTAL, portalId.toString());
    }
}
