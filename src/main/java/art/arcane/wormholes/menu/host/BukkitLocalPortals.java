package art.arcane.wormholes.menu.host;

import java.util.Optional;
import java.util.UUID;

import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;

final class BukkitLocalPortals {
    private BukkitLocalPortals() {
    }

    static Optional<LocalPortal> local(PortalManager portals, UUID portalId) {
        ILocalPortal portal = portals.getLocalPortal(portalId);
        if (portal instanceof LocalPortal local && !local.isDestroyed()) {
            return Optional.of(local);
        }
        return Optional.empty();
    }
}
