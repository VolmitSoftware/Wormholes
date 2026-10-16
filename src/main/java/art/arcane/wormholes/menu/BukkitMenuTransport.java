package art.arcane.wormholes.menu;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;

import art.arcane.wormholes.menu.protocol.MenuCodec;
import art.arcane.wormholes.menu.protocol.MenuProtocol;

public final class BukkitMenuTransport implements MenuTransport, PluginMessageListener, Listener {
    private final Plugin plugin;
    private final BukkitChestRenderer chestRenderer;
    private final Map<UUID, byte[]> heldAccepts = new ConcurrentHashMap<>();
    private volatile MenuServiceApi service;

    public BukkitMenuTransport(Plugin plugin, BukkitChestRenderer chestRenderer) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.chestRenderer = Objects.requireNonNull(chestRenderer, "chestRenderer");
    }

    public void start(MenuServiceApi menus) {
        service = Objects.requireNonNull(menus, "menus");
        Messenger messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, MenuProtocol.CHANNEL);
        messenger.registerIncomingPluginChannel(plugin, MenuProtocol.CHANNEL, this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void stop() {
        HandlerList.unregisterAll(this);
        Messenger messenger = plugin.getServer().getMessenger();
        messenger.unregisterIncomingPluginChannel(plugin, MenuProtocol.CHANNEL, this);
        messenger.unregisterOutgoingPluginChannel(plugin, MenuProtocol.CHANNEL);
        heldAccepts.clear();
        service = null;
    }

    @Override
    public boolean send(UUID viewerId, byte[] frame) {
        Player player = plugin.getServer().getPlayer(viewerId);
        if (player == null || !player.isOnline()) {
            return false;
        }
        if (player.getListeningPluginChannels().contains(MenuProtocol.CHANNEL)) {
            player.sendPluginMessage(plugin, MenuProtocol.CHANNEL, frame);
            return true;
        }
        if (frame.length > 0 && frame[0] == MenuCodec.S2C_ACCEPT) {
            heldAccepts.put(viewerId, frame.clone());
            return true;
        }
        return false;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        MenuServiceApi menus = service;
        if (menus == null || !MenuProtocol.CHANNEL.equals(channel)) {
            return;
        }
        menus.receive(player.getUniqueId(), message);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void on(PlayerRegisterChannelEvent event) {
        if (!MenuProtocol.CHANNEL.equals(event.getChannel())) {
            return;
        }
        Player player = event.getPlayer();
        byte[] accept = heldAccepts.remove(player.getUniqueId());
        if (accept != null) {
            player.sendPluginMessage(plugin, MenuProtocol.CHANNEL, accept);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void on(PlayerQuitEvent event) {
        UUID viewerId = event.getPlayer().getUniqueId();
        heldAccepts.remove(viewerId);
        chestRenderer.disconnected(viewerId);
        MenuServiceApi menus = service;
        if (menus != null) {
            menus.quit(viewerId);
        }
    }
}
