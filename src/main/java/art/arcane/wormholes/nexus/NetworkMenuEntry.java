package art.arcane.wormholes.nexus;

import art.arcane.volmlib.util.inventorygui.Window;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.wormholes.hook.PortalMenuEntry;
import art.arcane.wormholes.localization.NexusMessages;
import art.arcane.wormholes.portal.LocalPortal;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** The "Network" tile in the portal "More settings" grid. */
public final class NetworkMenuEntry implements PortalMenuEntry {
    private final NetworkRegistry registry;
    private final NetworkMenu menu;

    public NetworkMenuEntry(NetworkRegistry registry, NetworkMenu menu) {
        this.registry = registry;
        this.menu = menu;
    }

    @Override
    public String id() {
        return "nexus-network";
    }

    @Override
    public Material icon() {
        return Material.COMPASS;
    }

    @Override
    public LinesKey label() {
        return NexusMessages.MENU_ENTRY;
    }

    @Override
    public MessageArgs arguments(LocalPortal portal, Player viewer) {
        NexusPortalExtension state = portal.extension(NexusPortalExtension.class);
        PortalNetwork network = state == null ? null : registry.byId(state.networkId());
        return NexusText.args(
                "name", network == null ? "" : network.name(),
                "address", state == null ? "" : state.address());
    }

    @Override
    public boolean enchanted(LocalPortal portal, Player viewer) {
        NexusPortalExtension state = portal.extension(NexusPortalExtension.class);
        return state != null && state.isOnNetwork();
    }

    @Override
    public void onLeftClick(LocalPortal portal, Player viewer, Window window) {
        window.close();
        menu.open(portal, viewer);
    }
}
