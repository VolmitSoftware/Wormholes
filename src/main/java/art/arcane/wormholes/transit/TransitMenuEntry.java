package art.arcane.wormholes.transit;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import art.arcane.volmlib.util.inventorygui.Window;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.wormholes.hook.PortalMenuEntry;
import art.arcane.wormholes.localization.TransitMessages;
import art.arcane.wormholes.portal.LocalPortal;

/** The "Transit" tile in the portal's More settings grid; opens {@link TransitMenu}. */
public final class TransitMenuEntry implements PortalMenuEntry {
    public static final String ID = "transit";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Material icon() {
        return Material.FEATHER;
    }

    @Override
    public LinesKey label() {
        return TransitMessages.MENU_ENTRY;
    }

    @Override
    public boolean visible(LocalPortal portal, Player viewer) {
        return portal.extension(TransitPortalExtension.class) != null;
    }

    @Override
    public boolean enchanted(LocalPortal portal, Player viewer) {
        TransitPortalExtension transit = portal.extension(TransitPortalExtension.class);
        return transit != null && (transit.momentum() != null || transit.orientation() != null
            || transit.isMembrane() || transit.isBounce() || !transit.profile().isNone());
    }

    @Override
    public void onLeftClick(LocalPortal portal, Player viewer, Window window) {
        window.close();
        new TransitMenu(portal).open(viewer);
    }
}
