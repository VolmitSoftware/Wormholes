package art.arcane.wormholes.menu.portal;

import java.util.Optional;
import java.util.UUID;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.portal.PortalType;

public interface PortalMenuHost {
    Optional<PortalMenuView> portal(UUID portalId);

    boolean canManage(MenuViewer viewer, UUID portalId);

    boolean ownerOrAdmin(MenuViewer viewer, UUID portalId);

    boolean typeAllowed(MenuViewer viewer, PortalType type);

    boolean canEditSurfaceSkin(MenuViewer viewer, UUID portalId);

    PortalEditOutcome edit(MenuViewer viewer, UUID portalId, PortalEdit edit);

    void destroy(MenuViewer viewer, UUID portalId);

    void announce(UUID portalId, TextKey key, MessageArgs arguments, boolean success);

    int globalActivationRange();

    boolean beginDirectionPrompt(MenuViewer viewer, UUID portalId);

    String routerTitle(UUID portalId);
}
