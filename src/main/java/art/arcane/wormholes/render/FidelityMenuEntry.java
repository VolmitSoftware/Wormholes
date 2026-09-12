package art.arcane.wormholes.render;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import art.arcane.volmlib.util.inventorygui.Window;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.hook.PortalMenuEntry;
import art.arcane.wormholes.localization.FidelityMessages;
import art.arcane.wormholes.portal.LocalPortal;

/** "More settings" entry that opens the per-portal fidelity menu. */
public final class FidelityMenuEntry implements PortalMenuEntry {
    @Override
    public String id() {
        return "fidelity";
    }

    @Override
    public Material icon() {
        return Material.SPYGLASS;
    }

    @Override
    public LinesKey label() {
        return FidelityMessages.MENU_ENTRY;
    }

    @Override
    public boolean visible(LocalPortal portal, Player viewer) {
        return portal.extension(FidelityPortalExtension.class) != null;
    }

    @Override
    public void onLeftClick(LocalPortal portal, Player viewer, Window window) {
        window.close();
        FoliaScheduler.runEntity(Wormholes.instance, viewer, () -> new FidelityMenu(portal).open(viewer));
    }
}
