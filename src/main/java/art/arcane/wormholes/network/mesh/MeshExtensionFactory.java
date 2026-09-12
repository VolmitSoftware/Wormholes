package art.arcane.wormholes.network.mesh;

import art.arcane.wormholes.hook.PortalExtension;
import art.arcane.wormholes.hook.PortalExtensionFactory;
import art.arcane.wormholes.portal.LocalPortal;

public final class MeshExtensionFactory implements PortalExtensionFactory {
    @Override
    public Class<? extends PortalExtension> type() {
        return MeshPortalExtension.class;
    }

    @Override
    public PortalExtension create(LocalPortal portal) {
        return new MeshPortalExtension(portal);
    }
}
