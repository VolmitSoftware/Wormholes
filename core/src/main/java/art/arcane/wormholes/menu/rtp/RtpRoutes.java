package art.arcane.wormholes.menu.rtp;

import java.util.UUID;

import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.portal.PortalRoutes;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.ManualAction;

public final class RtpRoutes {
    public static final String CONTROLLER = PortalRoutes.RTP_CONTROLLER;
    public static final String OVERVIEW = PortalRoutes.RTP_OVERVIEW;
    public static final String DESTINATION = "rtp.destination";
    public static final String BIOME = "rtp.biome";
    public static final String LANDING = "rtp.landing";
    public static final String ROUTING = "rtp.routing";
    public static final String EFFECTS = "rtp.effects";
    public static final String NUMERIC = "rtp.numeric";
    public static final String CONFIRM = "rtp.confirm";
    public static final String FIELD = "field";
    public static final String ACTION = "action";

    private RtpRoutes() {
    }

    public static MenuRoute overview(UUID portalId) {
        return PortalRoutes.rtpOverview(portalId);
    }

    public static MenuRoute destination(UUID portalId) {
        return route(DESTINATION, portalId);
    }

    public static MenuRoute biome(UUID portalId) {
        return route(BIOME, portalId);
    }

    public static MenuRoute landing(UUID portalId) {
        return route(LANDING, portalId);
    }

    public static MenuRoute routing(UUID portalId) {
        return route(ROUTING, portalId);
    }

    public static MenuRoute effects(UUID portalId) {
        return route(EFFECTS, portalId);
    }

    public static MenuRoute numeric(UUID portalId, RtpNumericField field) {
        return route(NUMERIC, portalId).with(FIELD, field.name());
    }

    public static MenuRoute confirm(UUID portalId, ManualAction action) {
        return route(CONFIRM, portalId).with(ACTION, action.name());
    }

    public static RtpNumericField field(MenuRoute route) {
        return RtpNumericField.valueOf(route.params().get(FIELD));
    }

    public static ManualAction action(MenuRoute route) {
        return ManualAction.valueOf(route.params().get(ACTION));
    }

    private static MenuRoute route(String screen, UUID portalId) {
        return MenuRoute.of(CONTROLLER, screen).with(PortalRoutes.PORTAL, portalId.toString());
    }
}
