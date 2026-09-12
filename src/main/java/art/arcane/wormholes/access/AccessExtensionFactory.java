package art.arcane.wormholes.access;

import art.arcane.wormholes.hook.PortalExtension;
import art.arcane.wormholes.hook.PortalExtensionFactory;
import art.arcane.wormholes.portal.LocalPortal;

/** Attaches {@link AccessPortalExtension} to every constructed or loaded frame portal. */
public final class AccessExtensionFactory implements PortalExtensionFactory {
    @Override
    public Class<? extends PortalExtension> type() {
        return AccessPortalExtension.class;
    }

    @Override
    public PortalExtension create(LocalPortal portal) {
        return new AccessPortalExtension(portal);
    }
}
