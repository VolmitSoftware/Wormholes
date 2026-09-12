package art.arcane.wormholes.render;

import art.arcane.wormholes.hook.PortalExtension;
import art.arcane.wormholes.hook.PortalExtensionFactory;
import art.arcane.wormholes.portal.LocalPortal;

public final class FidelityExtensionFactory implements PortalExtensionFactory {
    @Override
    public Class<? extends PortalExtension> type() {
        return FidelityPortalExtension.class;
    }

    @Override
    public PortalExtension create(LocalPortal portal) {
        return new FidelityPortalExtension();
    }
}
