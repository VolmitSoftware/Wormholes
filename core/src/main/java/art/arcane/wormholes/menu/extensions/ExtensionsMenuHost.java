package art.arcane.wormholes.menu.extensions;

import java.util.List;
import java.util.UUID;

import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.portal.MenuExtensionEntry;

public interface ExtensionsMenuHost {
    List<MenuExtensionEntry> entries();

    boolean activate(MenuViewer viewer, UUID portalId, String entryId, MenuInteraction interaction);
}
