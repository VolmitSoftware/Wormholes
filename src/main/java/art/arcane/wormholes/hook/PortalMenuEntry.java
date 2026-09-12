package art.arcane.wormholes.hook;

import art.arcane.volmlib.util.inventorygui.Window;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.wormholes.portal.LocalPortal;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * One lane-owned entry in the portal "More settings" menu. The label is a lines key (name + lore)
 * from the lane's message group. Click handlers run on the viewer's thread inside the open window;
 * close the window before opening a submenu.
 */
public interface PortalMenuEntry {
    String id();

    Material icon();

    LinesKey label();

    default MessageArgs arguments(LocalPortal portal, Player viewer) {
        return MessageArgs.empty();
    }

    default boolean visible(LocalPortal portal, Player viewer) {
        return true;
    }

    default boolean enchanted(LocalPortal portal, Player viewer) {
        return false;
    }

    void onLeftClick(LocalPortal portal, Player viewer, Window window);

    default void onRightClick(LocalPortal portal, Player viewer, Window window) {
    }

    default void onShiftLeftClick(LocalPortal portal, Player viewer, Window window) {
    }
}
