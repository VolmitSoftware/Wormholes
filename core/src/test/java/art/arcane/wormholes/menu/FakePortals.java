package art.arcane.wormholes.menu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.menu.portal.PortalEdit;
import art.arcane.wormholes.menu.portal.PortalEditOutcome;
import art.arcane.wormholes.menu.portal.PortalMenuHost;
import art.arcane.wormholes.menu.portal.PortalMenuView;
import art.arcane.wormholes.portal.PortalType;

public final class FakePortals implements PortalMenuHost {
    public static final UUID PORTAL = UUID.fromString("00000000-0000-0000-0000-00000000c0a1");

    public final Map<UUID, PortalMenuView> views = new HashMap<>();
    public final Set<UUID> coOwners = new HashSet<>();
    public final List<String> announcements = new ArrayList<>();

    public FakePortals(UUID owner) {
        put(base(PORTAL, "Alpha").owner(owner).build());
    }

    public static PortalMenuView.Builder base(UUID id, String name) {
        return PortalMenuView.builder(id, name)
            .routerTitle("§0§l" + name)
            .frame(Frame.canonical(Face.S))
            .accessibleCount(2)
            .permissionNode("wormholes.portal." + name.toLowerCase(Locale.ROOT));
    }

    public void put(PortalMenuView view) {
        views.put(view.id(), view);
    }

    public PortalMenuView view() {
        return views.get(PORTAL);
    }

    @Override
    public Optional<PortalMenuView> portal(UUID portalId) {
        return Optional.ofNullable(views.get(portalId));
    }

    @Override
    public boolean canManage(MenuViewer viewer, UUID portalId) {
        return ownerOrAdmin(viewer, portalId) || views.containsKey(portalId) && coOwners.contains(viewer.id());
    }

    @Override
    public boolean ownerOrAdmin(MenuViewer viewer, UUID portalId) {
        PortalMenuView view = views.get(portalId);
        return view != null && (viewer.administrator() || viewer.id().equals(view.owner()));
    }

    @Override
    public boolean typeAllowed(MenuViewer viewer, PortalType type) {
        return true;
    }

    @Override
    public boolean canEditSurfaceSkin(MenuViewer viewer, UUID portalId) {
        return viewer.administrator();
    }

    @Override
    public PortalEditOutcome edit(MenuViewer viewer, UUID portalId, PortalEdit edit) {
        throw new AssertionError("unexpected portal edit " + edit);
    }

    @Override
    public void destroy(MenuViewer viewer, UUID portalId) {
        throw new AssertionError("unexpected destroy");
    }

    @Override
    public void announce(UUID portalId, TextKey key, MessageArgs arguments, boolean success) {
        announcements.add(FakeStrings.render(key.english(), arguments));
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
        return views.get(portalId).routerTitle();
    }
}
