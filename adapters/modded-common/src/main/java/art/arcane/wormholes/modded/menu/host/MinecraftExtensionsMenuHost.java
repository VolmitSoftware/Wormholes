package art.arcane.wormholes.modded.menu.host;

import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.extensions.ExtensionsMenuHost;
import art.arcane.wormholes.menu.portal.MenuExtensionEntry;

import java.util.List;
import java.util.UUID;

public final class MinecraftExtensionsMenuHost implements ExtensionsMenuHost {
    private final List<MenuExtensionEntry> entries;

    public MinecraftExtensionsMenuHost(List<MenuExtensionEntry> entries) {
        this.entries = List.copyOf(entries);
    }

    @Override
    public List<MenuExtensionEntry> entries() {
        return entries;
    }

    @Override
    public boolean activate(MenuViewer viewer, UUID portalId, String entryId, MenuInteraction interaction) {
        return false;
    }
}
