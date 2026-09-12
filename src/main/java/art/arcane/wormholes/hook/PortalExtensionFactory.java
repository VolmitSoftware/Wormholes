package art.arcane.wormholes.hook;

import art.arcane.wormholes.portal.LocalPortal;

/** Creates a {@link PortalExtension} for every constructed or loaded {@code LocalPortal}. */
public interface PortalExtensionFactory {
    Class<? extends PortalExtension> type();

    PortalExtension create(LocalPortal portal);
}
