package art.arcane.wormholes.nexus;

import art.arcane.wormholes.hook.PortalExtension;
import art.arcane.wormholes.hook.PortalExtensionFactory;
import art.arcane.wormholes.portal.LocalPortal;

/** Attaches {@link NexusPortalExtension} to every constructed or loaded portal. */
public final class NexusExtensionFactory implements PortalExtensionFactory {
    private final NexusPortalListener listener;

    public NexusExtensionFactory(NexusPortalListener listener) {
        this.listener = listener;
    }

    @Override
    public Class<? extends PortalExtension> type() {
        return NexusPortalExtension.class;
    }

    @Override
    public PortalExtension create(LocalPortal portal) {
        return new NexusPortalExtension(portal, listener);
    }
}
