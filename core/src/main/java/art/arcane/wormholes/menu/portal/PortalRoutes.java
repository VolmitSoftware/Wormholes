package art.arcane.wormholes.menu.portal;

import java.util.UUID;

import art.arcane.wormholes.menu.MenuRoute;

public final class PortalRoutes {
    public static final String CONTROLLER = "portal";
    public static final String HOME = "portal.home";
    public static final String MODE = "portal.mode";
    public static final String ORIENTATION = "portal.orientation";
    public static final String GATEWAY = "portal.gateway";
    public static final String PORTAL = "portal";
    public static final String SETTINGS_CONTROLLER = "settings";
    public static final String SETTINGS_MAIN = "settings.main";
    public static final String COST_CONTROLLER = "cost";
    public static final String COST_MAIN = "cost.main";
    public static final String EXTENSIONS_CONTROLLER = "extensions";
    public static final String EXTENSIONS_MAIN = "extensions.main";
    public static final String DESTINATION_CONTROLLER = "destination";
    public static final String DESTINATION_PICKER = "destination.picker";
    public static final String RTP_CONTROLLER = "rtp";
    public static final String RTP_OVERVIEW = "rtp.overview";

    private PortalRoutes() {
    }

    public static MenuRoute home(UUID portalId) {
        return route(CONTROLLER, HOME, portalId);
    }

    public static MenuRoute mode(UUID portalId) {
        return route(CONTROLLER, MODE, portalId);
    }

    public static MenuRoute orientation(UUID portalId) {
        return route(CONTROLLER, ORIENTATION, portalId);
    }

    public static MenuRoute gateway(UUID portalId) {
        return route(CONTROLLER, GATEWAY, portalId);
    }

    public static MenuRoute settings(UUID portalId) {
        return route(SETTINGS_CONTROLLER, SETTINGS_MAIN, portalId);
    }

    public static MenuRoute cost(UUID portalId) {
        return route(COST_CONTROLLER, COST_MAIN, portalId);
    }

    public static MenuRoute extensions(UUID portalId) {
        return route(EXTENSIONS_CONTROLLER, EXTENSIONS_MAIN, portalId);
    }

    public static MenuRoute destinationPicker(UUID portalId) {
        return route(DESTINATION_CONTROLLER, DESTINATION_PICKER, portalId);
    }

    public static MenuRoute rtpOverview(UUID portalId) {
        return route(RTP_CONTROLLER, RTP_OVERVIEW, portalId);
    }

    public static UUID portalId(MenuRoute route) {
        return route.uuid(PORTAL);
    }

    private static MenuRoute route(String controllerId, String screen, UUID portalId) {
        return MenuRoute.of(controllerId, screen).with(PORTAL, portalId.toString());
    }
}
