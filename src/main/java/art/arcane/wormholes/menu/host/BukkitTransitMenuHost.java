package art.arcane.wormholes.menu.host;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.TransitConfig;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.transit.TransitEdit;
import art.arcane.wormholes.menu.transit.TransitMenuHost;
import art.arcane.wormholes.menu.transit.TransitMenuView;
import art.arcane.wormholes.network.PortalSyncService;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.transit.TransitPortalExtension;
import art.arcane.wormholes.transit.TransitSubsystem;

public final class BukkitTransitMenuHost implements TransitMenuHost {
    private final PortalManager portals;

    public BukkitTransitMenuHost(PortalManager portals) {
        this.portals = Objects.requireNonNull(portals, "portals");
    }

    @Override
    public Optional<TransitMenuView> transit(UUID portalId) {
        return extended(portalId).map(portal -> view(portal.extension(TransitPortalExtension.class)));
    }

    @Override
    public boolean supportsGravityRotation() {
        return false;
    }

    @Override
    public void apply(MenuViewer viewer, UUID portalId, TransitEdit edit) {
        Optional<LocalPortal> found = extended(portalId);
        if (found.isEmpty()) {
            return;
        }
        LocalPortal portal = found.get();
        TransitPortalExtension transit = portal.extension(TransitPortalExtension.class);
        switch (edit) {
            case TransitEdit.Gravity _ -> throw new IllegalArgumentException("directional gravity is not available on this platform");
            case TransitEdit.Momentum momentum -> transit.setMomentum(momentum.policy());
            case TransitEdit.Orientation orientation -> transit.setOrientation(orientation.policy());
            case TransitEdit.Membrane membrane -> transit.setMembrane(membrane.enabled());
            case TransitEdit.Bounce bounce -> transit.setBounce(bounce.enabled());
            case TransitEdit.Profile profile -> transit.setProfile(profile.profile());
            case TransitEdit.Scale scale -> transit.setScaleRule(scale.rule());
        }
        portal.save();
        portal.invalidateMenus();
        PortalSyncService<ILocalPortal> sync = Wormholes.portalSyncService;
        if (portal.isSettingsSyncEnabled() && sync != null && !PortalSyncService.isApplyingRemote()) {
            sync.broadcastSettings(portal);
        }
    }

    private Optional<LocalPortal> extended(UUID portalId) {
        return BukkitLocalPortals.local(portals, portalId)
            .filter(portal -> portal.extension(TransitPortalExtension.class) != null);
    }

    private static TransitMenuView view(TransitPortalExtension transit) {
        TransitConfig config = TransitSubsystem.config();
        return new TransitMenuView(transit.momentum(), transit.effectiveMomentum(config), transit.orientation(), transit.effectiveOrientation(config),
            transit.isMembrane(), transit.isBounce(), transit.profile(), transit.scaleRule(), false);
    }
}
