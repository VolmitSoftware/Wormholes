package art.arcane.wormholes.network.mesh;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.hook.WormholesSubsystem;
import art.arcane.wormholes.network.ImportExportService;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.PortalInfo;
import art.arcane.wormholes.network.PortalSyncService;
import art.arcane.wormholes.network.RemotePortalRegistry;
import art.arcane.wormholes.network.WireCapability;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.proxy.protocol.ProxySecret;

import org.bukkit.event.HandlerList;
import org.bukkit.plugin.messaging.Messenger;

import java.util.ArrayList;
import java.util.List;

/**
 * Lifecycle entry point for the mesh lane. The federation frames themselves are dispatched inside
 * {@link NetworkManager} (one handler per manager, so multi-manager tests work); this subsystem binds
 * the pieces that need the plugin runtime: registry cleanup on network-wide removal, the Bukkit
 * load source behind the beacons, portal queries over the local portal manager, and the persisted
 * remote directory (hydrated stale, then recorded on every change).
 */
public final class MeshSubsystem implements WormholesSubsystem {
    private ProxyBridge bridge;
    private boolean secretWarned;

    @Override
    public String id() {
        return "mesh";
    }

    @Override
    public void register(WormholesRegistrar registrar) {
        registrar.portalExtension(new MeshExtensionFactory());
    }

    @Override
    public void start(Wormholes plugin) {
        bind();
        bindProxy(plugin);
    }

    @Override
    public void stop() {
        unbindProxy(Wormholes.instance);
    }

    @Override
    public void onSettingsReloaded(WormholesSettings settings) {
        bind();
        bindProxy(Wormholes.instance);
    }

    /**
     * Registers the proxy channel and bridge while [network.proxy] enabled is true and a shared secret
     * is set; tears them down when either flips off. Without a secret nothing can tell a proxy frame
     * from a client's, so the module stays off rather than trusting whatever arrives.
     */
    private void bindProxy(Wormholes plugin) {
        if (plugin == null) {
            return;
        }
        NetworkConfig.ProxyConfig proxy = Wormholes.settings.getNetwork().proxy;
        byte[] secret = ProxySecret.of(proxy.secret);
        boolean enabled = proxy.enabled && secret != null;
        String channel = proxy.channel;
        if (bridge != null && (!enabled || !bridge.channel().equals(channel) || !bridge.matchesSecret(secret))) {
            unbindProxy(plugin);
        }
        if (proxy.enabled && secret == null && !secretWarned) {
            secretWarned = true;
            plugin.getLogger().warning("net: proxy module off, [network.proxy] secret is unset (copy plugins/WormholesProxy/" + ProxySecret.FILE + ")");
        }
        ImportExportService importExport = Wormholes.importExportService;
        if (!enabled || bridge != null || importExport == null) {
            return;
        }
        secretWarned = false;
        ProxyBridge fresh = new ProxyBridge(channel, secret, importExport::localServerCode, MeshSubsystem::shareablePortalNames,
            () -> WireCapability.localSet(Wormholes.settings.getNetwork()), importExport::importProxyServerCode, System::currentTimeMillis);
        Messenger messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, channel);
        messenger.registerIncomingPluginChannel(plugin, channel, fresh);
        plugin.getServer().getPluginManager().registerEvents(fresh, plugin);
        bridge = fresh;
        ProxyBridge.setActive(fresh);
        plugin.getLogger().info("net: proxy module channel " + channel + " enrolled");
    }

    private void unbindProxy(Wormholes plugin) {
        ProxyBridge current = bridge;
        bridge = null;
        ProxyBridge.setActive(null);
        if (current == null || plugin == null) {
            return;
        }
        Messenger messenger = plugin.getServer().getMessenger();
        messenger.unregisterIncomingPluginChannel(plugin, current.channel(), current);
        messenger.unregisterOutgoingPluginChannel(plugin, current.channel());
        HandlerList.unregisterAll(current);
    }

    private static List<String> shareablePortalNames() {
        List<String> names = new ArrayList<>();
        for (PortalInfo info : shareableLocalPortals()) {
            names.add(info.name());
        }
        return names;
    }

    private static void bind() {
        NetworkManager network = Wormholes.networkManager;
        if (network == null) {
            return;
        }
        network.mesh().setPeerRemovedListener(name -> {
            RemotePortalRegistry registry = Wormholes.remotePortalRegistry;
            if (registry != null) {
                registry.removePeer(name);
            }
        });
        network.beacons().setSource(new BukkitServerLoadSource());
        network.mesh().setPortalQueryService(PortalQueryService.forNetwork(network, MeshSubsystem::shareableLocalPortals));
        RemotePortalRegistry registry = Wormholes.remotePortalRegistry;
        if (registry != null && Wormholes.settings.getNetwork().directoryCacheEnabled) {
            DirectoryCache cache = network.directoryCache();
            cache.hydrate(registry);
            registry.setListener(cache);
        }
    }

    private static List<PortalInfo> shareableLocalPortals() {
        List<PortalInfo> infos = new ArrayList<>();
        if (Wormholes.portalManager == null) {
            return infos;
        }
        for (ILocalPortal portal : Wormholes.portalManager.getLocalPortals()) {
            if (PortalSyncService.isShareable(portal)) {
                infos.add(PortalSyncService.toInfo(portal));
            }
        }
        return infos;
    }
}
