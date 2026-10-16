package art.arcane.wormholes.menu.extensions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.portal.MenuExtensionEntry;

final class FakeExtensionsMenuHost implements ExtensionsMenuHost {
    final List<MenuExtensionEntry> entries = new ArrayList<>();
    final List<String> activations = new ArrayList<>();
    boolean takesOver = true;
    Runnable onActivate = () -> {
    };

    @Override
    public List<MenuExtensionEntry> entries() {
        return entries;
    }

    @Override
    public boolean activate(MenuViewer viewer, UUID portalId, String entryId, MenuInteraction interaction) {
        activations.add(entryId + ":" + interaction);
        onActivate.run();
        return takesOver;
    }
}
