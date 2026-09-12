package art.arcane.wormholes.rules;

import art.arcane.volmlib.util.inventorygui.Window;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.hook.PortalMenuEntry;
import art.arcane.wormholes.localization.RulesMessages;
import art.arcane.wormholes.portal.LocalPortal;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** The "Rules" tile in the portal "More settings" grid. */
public final class RulesMenuEntry implements PortalMenuEntry {
    @Override
    public String id() {
        return "rules";
    }

    @Override
    public Material icon() {
        return Material.BOOK;
    }

    @Override
    public LinesKey label() {
        return RulesMessages.MENU_ENTRY;
    }

    @Override
    public boolean enchanted(LocalPortal portal, Player viewer) {
        RulesPortalExtension extension = portal.extension(RulesPortalExtension.class);
        return extension != null && !extension.document().isInert();
    }

    @Override
    public void onLeftClick(LocalPortal portal, Player viewer, Window window) {
        window.close();
        viewer.closeInventory();
        FoliaScheduler.runEntity(Wormholes.instance, viewer, () -> new RulesMenu(portal).open(viewer));
    }
}
