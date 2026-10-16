package art.arcane.wormholes.menu.host;

import java.util.List;
import java.util.UUID;

import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.extensions.ExtensionsMenuHost;
import art.arcane.wormholes.menu.portal.MenuExtensionEntry;

public final class BukkitExtensionsMenuHost implements ExtensionsMenuHost {
    @Override
    public List<MenuExtensionEntry> entries() {
        return WormholesHooks.portalMenuEntries();
    }

    @Override
    public boolean activate(MenuViewer viewer, UUID portalId, String entryId, MenuInteraction interaction) {
        return false;
    }
}
