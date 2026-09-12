package art.arcane.wormholes.rules;

import art.arcane.wormholes.hook.PortalExtension;
import art.arcane.wormholes.hook.PortalExtensionFactory;
import art.arcane.wormholes.portal.LocalPortal;

/** Attaches a {@link RulesPortalExtension} to every constructed or loaded portal. */
public final class RulesExtensionFactory implements PortalExtensionFactory {
    @Override
    public Class<? extends PortalExtension> type() {
        return RulesPortalExtension.class;
    }

    @Override
    public PortalExtension create(LocalPortal portal) {
        return new RulesPortalExtension(portal);
    }
}
