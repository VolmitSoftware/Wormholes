package art.arcane.wormholes.menu;

import java.util.UUID;

import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.menu.portal.PortalMenuHost;

public final class MenuGuards {
    private MenuGuards() {
    }

    public static MenuGuard none() {
        return (context, action) -> MenuDecision.allow();
    }

    public static MenuGuard manage(PortalMenuHost host, UUID portalId) {
        return (context, action) -> host.canManage(context.viewer(), portalId)
            ? MenuDecision.allow()
            : MenuDecision.denyAndClose(MenuNotices.hud(context.viewer(), WormholesMessages.PORTAL_EDIT_DENIED));
    }

    public static MenuGuard ownerOrAdmin(PortalMenuHost host, UUID portalId) {
        return (context, action) -> host.ownerOrAdmin(context.viewer(), portalId)
            ? MenuDecision.allow()
            : MenuDecision.deny(MenuNotices.hud(context.viewer(), WormholesMessages.PORTAL_EDIT_DENIED));
    }

    public static MenuGuard surfaceSkin(PortalMenuHost host, UUID portalId) {
        return (context, action) -> host.canEditSurfaceSkin(context.viewer(), portalId)
            ? MenuDecision.allow()
            : MenuDecision.deny(MenuNotices.hud(context.viewer(), WormholesMessages.PORTAL_EDIT_DENIED));
    }

    public static MenuGuard administrator() {
        return (context, action) -> context.viewer().administrator()
            ? MenuDecision.allow()
            : MenuDecision.deny(MenuNotices.hud(context.viewer(), WormholesMessages.COMMAND_NO_PERMISSION));
    }

    public static MenuGuard permission(String node) {
        return (context, action) -> context.viewer().hasPermission(node)
            ? MenuDecision.allow()
            : MenuDecision.deny(MenuNotices.hud(context.viewer(), WormholesMessages.COMMAND_NO_PERMISSION));
    }
}
