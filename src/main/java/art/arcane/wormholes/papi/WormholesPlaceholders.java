package art.arcane.wormholes.papi;

import art.arcane.volmlib.util.bukkit.papi.PlaceholderKeyRegistry;
import art.arcane.volmlib.util.bukkit.papi.PlaceholderRegistration;
import art.arcane.volmlib.util.bukkit.papi.PlaceholderSnapshot;
import art.arcane.volmlib.util.bukkit.papi.PlaceholderValues;
import art.arcane.volmlib.util.bukkit.papi.PlayerSnapshotStore;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;

public final class WormholesPlaceholders implements Listener {
    private static final String PLACEHOLDER_API = "PlaceholderAPI";

    private final PlaceholderSnapshot<WormholesRuntimeSnapshot> runtime = new PlaceholderSnapshot<>();
    private final PlayerSnapshotStore<WormholesPortalSnapshot> portals = new PlayerSnapshotStore<>();
    private final PlaceholderKeyRegistry keys = registry(runtime, portals);
    private final PlaceholderRegistration registration;
    private final String version;
    private final Logger logger;

    public WormholesPlaceholders(String version, Logger logger) {
        this.version = Objects.requireNonNull(version, "version");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.registration = new PlaceholderRegistration(this.logger);
    }

    public void install(Plugin plugin) {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        register();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        if (!PLACEHOLDER_API.equals(event.getPlugin().getName())) {
            return;
        }

        register();
    }

    public boolean register() {
        if (!PlaceholderRegistration.isPlaceholderApiEnabled()) {
            return false;
        }

        return WormholesPlaceholderInstaller.install(registration, version, keys, logger);
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
        registration.unregister();
        portals.clear();
        runtime.publish(null);
    }

    public void publishRuntime(WormholesRuntimeSnapshot snapshot) {
        runtime.publish(snapshot);
    }

    public void publishPortal(UUID playerId, WormholesPortalSnapshot snapshot) {
        portals.publish(playerId, snapshot);
    }

    public void forget(UUID playerId) {
        portals.evictAfterGrace(playerId, PlayerSnapshotStore.DEFAULT_GRACE_MS);
    }

    public String resolve(UUID playerId, String key) {
        return keys.resolve(playerId, key);
    }

    public static PlaceholderKeyRegistry registry(
        PlaceholderSnapshot<WormholesRuntimeSnapshot> runtime,
        PlayerSnapshotStore<WormholesPortalSnapshot> portals) {
        return PlaceholderKeyRegistry.builder()
            .key(PlaceholderKeyRegistry.AVAILABLE, playerId -> runtime.available())
            .key("portals", playerId -> runtimeValue(runtime, WormholesRuntimeSnapshot::portals))
            .key("projections.active", playerId -> runtimeValue(runtime, WormholesRuntimeSnapshot::projectionsActive))
            .key("projections.observers", playerId -> runtimeValue(runtime, WormholesRuntimeSnapshot::projectionsObservers))
            .key("peers.connected", playerId -> runtimeValue(runtime, WormholesRuntimeSnapshot::peersConnected))
            .key("peers.link", playerId -> runtimeValue(runtime, WormholesRuntimeSnapshot::peersLink))
            .key("transfers.in-flight", playerId -> runtimeValue(runtime, WormholesRuntimeSnapshot::transfersInFlight))
            .key("failures", playerId -> runtimeValue(runtime, WormholesRuntimeSnapshot::failures))
            .key("failures.per-minute", playerId -> runtimeValue(runtime, WormholesRuntimeSnapshot::failuresPerMinute))
            .key("portal.available", portals::available)
            .key("portal.name", playerId -> portalValue(portals, playerId, WormholesPortalSnapshot::name))
            .key("portal.state", playerId -> portalValue(portals, playerId, WormholesPortalSnapshot::state))
            .key("portal.destination", playerId -> portalValue(portals, playerId, WormholesPortalSnapshot::destination))
            .key("portal.distance", playerId -> portalValue(portals, playerId, WormholesPortalSnapshot::distance))
            .key("portal.cross-server", playerId -> portalValue(portals, playerId, WormholesPortalSnapshot::crossServer))
            .key("rtp.state", playerId -> portalValue(portals, playerId, WormholesPortalSnapshot::rtpState))
            .key("rtp.cooldown", playerId -> portalValue(portals, playerId, WormholesPortalSnapshot::rtpCooldown))
            .build();
    }

    private static String runtimeValue(PlaceholderSnapshot<WormholesRuntimeSnapshot> runtime, Function<WormholesRuntimeSnapshot, String> field) {
        WormholesRuntimeSnapshot snapshot = runtime.get();
        return snapshot == null ? PlaceholderValues.UNAVAILABLE : field.apply(snapshot);
    }

    private static String portalValue(PlayerSnapshotStore<WormholesPortalSnapshot> portals, UUID playerId, Function<WormholesPortalSnapshot, String> field) {
        WormholesPortalSnapshot snapshot = portals.get(playerId);
        return snapshot == null ? PlaceholderValues.UNAVAILABLE : field.apply(snapshot);
    }
}
