package art.arcane.wormholes.access;

import art.arcane.volmlib.util.inventorygui.Window;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.wormholes.hook.PortalMenuEntry;
import art.arcane.wormholes.localization.AccessMessages;
import art.arcane.wormholes.portal.LocalPortal;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** "Access" tile in the portal "More settings" grid. */
public final class AccessMenuEntry implements PortalMenuEntry {
    private final AccessMenu menu = new AccessMenu();

    @Override
    public String id() {
        return "access";
    }

    @Override
    public Material icon() {
        return Material.NAME_TAG;
    }

    @Override
    public LinesKey label() {
        return AccessMessages.MENU_ENTRY;
    }

    @Override
    public boolean enchanted(LocalPortal portal, Player viewer) {
        AccessPortalExtension access = portal.extension(AccessPortalExtension.class);
        return access != null && !access.roles().isEmpty();
    }

    @Override
    public void onLeftClick(LocalPortal portal, Player viewer, Window window) {
        window.close();
        menu.open(portal, viewer);
    }
}
