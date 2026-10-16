package art.arcane.wormholes.menu.rules;

import java.util.Objects;
import java.util.UUID;

import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.portal.PortalRoutes;

public final class RulesRoutes {
    public static final String CONTROLLER = "rules";
    public static final String PROFILE = "rules.profile";
    public static final String LIST = "rules.list";
    public static final String RULE = "rules.rule";
    public static final String TEMPLATES = "rules.templates";
    public static final String RULE_ID = "rule";

    private RulesRoutes() {
    }

    public static MenuRoute profile(UUID portalId) {
        return route(PROFILE, portalId);
    }

    public static MenuRoute list(UUID portalId) {
        return route(LIST, portalId);
    }

    public static MenuRoute rule(UUID portalId, String ruleId) {
        return route(RULE, portalId).with(RULE_ID, Objects.requireNonNull(ruleId, "ruleId"));
    }

    public static MenuRoute templates(UUID portalId) {
        return route(TEMPLATES, portalId);
    }

    public static UUID portalId(MenuRoute route) {
        return PortalRoutes.portalId(route);
    }

    public static String ruleId(MenuRoute route) {
        return Objects.requireNonNull(route.params().get(RULE_ID), RULE_ID);
    }

    private static MenuRoute route(String screen, UUID portalId) {
        return MenuRoute.of(CONTROLLER, screen).with(PortalRoutes.PORTAL, portalId.toString());
    }
}
