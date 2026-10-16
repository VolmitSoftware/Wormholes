package art.arcane.wormholes.menu.rules;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.portal.PortalEdit;
import art.arcane.wormholes.menu.portal.PortalEditOutcome;
import art.arcane.wormholes.menu.portal.PortalMenuHost;
import art.arcane.wormholes.menu.portal.PortalMenuView;
import art.arcane.wormholes.portal.PortalType;

final class FakeRulesPortals implements PortalMenuHost {
    static final UUID PORTAL = UUID.fromString("00000000-0000-0000-0000-0000000a11e5");

    final Set<UUID> managers = new HashSet<>();
    final PortalMenuView portal = PortalMenuView.builder(PORTAL, "Alpha")
        .routerTitle("§0§lAlpha")
        .frame(Frame.canonical(Face.S))
        .accessibleCount(2)
        .build();
    boolean present = true;

    @Override
    public Optional<PortalMenuView> portal(UUID portalId) {
        return present && portalId.equals(PORTAL) ? Optional.of(portal) : Optional.empty();
    }

    @Override
    public boolean canManage(MenuViewer viewer, UUID portalId) {
        return viewer.administrator() || managers.contains(viewer.id());
    }

    @Override
    public boolean ownerOrAdmin(MenuViewer viewer, UUID portalId) {
        return canManage(viewer, portalId);
    }

    @Override
    public boolean typeAllowed(MenuViewer viewer, PortalType type) {
        return viewer.administrator();
    }

    @Override
    public boolean canEditSurfaceSkin(MenuViewer viewer, UUID portalId) {
        return viewer.administrator();
    }

    @Override
    public PortalEditOutcome edit(MenuViewer viewer, UUID portalId, PortalEdit edit) {
        throw new AssertionError("rules menus never edit portal settings");
    }

    @Override
    public void destroy(MenuViewer viewer, UUID portalId) {
        throw new AssertionError("rules menus never destroy portals");
    }

    @Override
    public void announce(UUID portalId, TextKey key, MessageArgs arguments, boolean success) {
        throw new AssertionError("rules menus never announce portal changes");
    }

    @Override
    public int globalActivationRange() {
        return 64;
    }

    @Override
    public boolean beginDirectionPrompt(MenuViewer viewer, UUID portalId) {
        return false;
    }

    @Override
    public String routerTitle(UUID portalId) {
        return portal.routerTitle();
    }
}
