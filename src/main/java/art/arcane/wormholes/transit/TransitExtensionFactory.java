package art.arcane.wormholes.transit;

import art.arcane.wormholes.hook.PortalExtension;
import art.arcane.wormholes.hook.PortalExtensionFactory;
import art.arcane.wormholes.portal.LocalPortal;

/** Attaches a {@link TransitPortalExtension} to every local portal. */
public final class TransitExtensionFactory implements PortalExtensionFactory {
    @Override
    public Class<? extends PortalExtension> type() {
        return TransitPortalExtension.class;
    }

    @Override
    public PortalExtension create(LocalPortal portal) {
        return new TransitPortalExtension();
    }
}
