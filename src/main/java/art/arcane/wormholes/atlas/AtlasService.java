package art.arcane.wormholes.atlas;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.AtlasConfig;
import art.arcane.wormholes.hook.TraversalAttempt;
import art.arcane.wormholes.hook.TraversalObserver;
import art.arcane.wormholes.localization.AtlasMessages;
import art.arcane.wormholes.nexus.NetworkRegistry;
import art.arcane.wormholes.nexus.NexusPortalExtension;
import art.arcane.wormholes.nexus.PortalNetwork;
import art.arcane.wormholes.nexus.Visibility;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.service.WormholesHud;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.function.Supplier;
import java.util.function.Consumer;

/**
 * The runtime behind {@code /atlas}: it discovers portals a player stands at, remembers the ones they
 * travel through, and draws the guide bearing. Discovery probes a chunk-bucketed index, never a scan.
 */
public final class AtlasService implements Listener, TraversalObserver {
    private static final Logger LOG = Logger.getLogger("Wormholes");
    private static final int INDEX_REBUILD_INTERVAL = 5;
    private static final long LOGIN_CACHE_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(1L);

    private final AtlasPlayerStore store;
    private final NetworkRegistry registry;
    private final Supplier<AtlasConfig> config;
    private final Supplier<List<ILocalPortal>> portals;
    private final AtlasProximityIndex<UUID> index = new AtlasProximityIndex<>();
    private final Object playersLock = new Object();
    private final Set<UUID> online = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> pendingLogins = new ConcurrentHashMap<>();
    private int ticksSinceRebuild = INDEX_REBUILD_INTERVAL;

    public AtlasService(AtlasPlayerStore store, NetworkRegistry registry, Supplier<AtlasConfig> config,
                        Supplier<List<ILocalPortal>> portals) {
        this.store = Objects.requireNonNull(store, "store");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.config = Objects.requireNonNull(config, "config");
        this.portals = Objects.requireNonNull(portals, "portals");
    }

    public AtlasPlayerStore store() {
        return store;
    }

    public AtlasPlayerState state(Player player) {
        return Objects.requireNonNull(store.cached(player.getUniqueId()), "Atlas state is not loaded");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void on(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            CompletableFuture<AtlasPlayerState> pending;
            synchronized (playersLock) {
                pendingLogins.put(event.getUniqueId(), System.nanoTime() + LOGIN_CACHE_TIMEOUT_NANOS);
                pending = store.loadAsync(event.getUniqueId());
            }
            pending.join();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void on(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) {
            UUID playerId = event.getPlayer().getUniqueId();
            synchronized (playersLock) {
                pendingLogins.remove(playerId);
                if (!online.contains(playerId)) {
                    store.unloadAsync(playerId);
                }
            }
        }
    }

    @EventHandler
    public void on(PlayerJoinEvent event) {
        loadOnline(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void on(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        synchronized (playersLock) {
            online.remove(playerId);
            store.unloadAsync(playerId);
        }
    }

    public void loadOnline(UUID playerId) {
        synchronized (playersLock) {
            online.add(playerId);
            pendingLogins.remove(playerId);
            store.loadAsync(playerId);
        }
    }

    @Override
    public void onDeparted(TraversalAttempt attempt) {
        if (!(attempt.traveler() instanceof Player player)) {
            return;
        }
        if (attempt.portal().getDimensionalPortalKind().isManagedPortal()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        UUID portalId = attempt.portal().getId();
        int recentLimit = config.get().recentLimit;
        AtlasPlayerState state = store.cached(playerId);
        if (state != null) {
            recordDeparture(state, portalId, recentLimit);
            return;
        }
        store.loadAsync(playerId).thenAccept(loaded -> recordDeparture(loaded, portalId, recentLimit))
            .exceptionally(failure -> {
                LOG.log(Level.WARNING, "Atlas departure could not be recorded for " + playerId, failure);
                return null;
            });
    }

    /** One discovery and guide pass over the online players. Runs on the one-second nexus task. */
    public void tick(Collection<? extends Player> onlinePlayers) {
        expirePendingLogins();
        AtlasConfig settings = config.get();
        if (!settings.enabled) {
            return;
        }
        if (settings.discoveryRequired && ++ticksSinceRebuild >= INDEX_REBUILD_INTERVAL) {
            ticksSinceRebuild = 0;
            index.rebuild(indexAnchors(portals.get()));
        }
        if (!settings.discoveryRequired && !settings.guideEnabled) {
            return;
        }
        for (Player player : onlinePlayers) {
            AtlasPlayerState state = store.cached(player.getUniqueId());
            if (state != null && (settings.discoveryRequired || state.guideTarget() != null)) {
                FoliaScheduler.runEntity(Wormholes.instance, player, () -> tickPlayer(player, settings));
            }
        }
    }

    public void withState(Player player, Consumer<AtlasPlayerState> action) {
        AtlasPlayerState state = store.cached(player.getUniqueId());
        if (state != null) {
            action.accept(state);
            return;
        }
        store.loadAsync(player.getUniqueId()).thenAccept(loaded ->
            FoliaScheduler.runEntity(Wormholes.instance, player, () -> {
                if (player.isOnline() && store.cached(player.getUniqueId()) == loaded) {
                    action.accept(loaded);
                }
            })).exceptionally(failure -> {
                LOG.log(Level.WARNING, "Atlas action failed for " + player.getUniqueId(), failure);
                return null;
            });
    }

    public AtlasPlayerState.FavoriteResult toggleFavorite(Player player, UUID portalId) {
        return state(player).toggleFavorite(portalId, config.get().favoritesLimit);
    }

    public void setGuideTarget(Player player, UUID portalId) {
        state(player).setGuideTarget(portalId);
        if (portalId == null) {
            WormholesHud.clearGuide(player);
        }
    }

    /** Drops a deleted portal from every loaded atlas so it stops showing up. */
    public void forgetPortal(UUID portalId) {
        if (Wormholes.instance == null) {
            return;
        }
        for (Player player : Wormholes.instance.getServer().getOnlinePlayers()) {
            AtlasPlayerState state = store.cached(player.getUniqueId());
            if (state != null) {
                state.forget(portalId);
            }
        }
    }

    /** Every portal this player may depart through, as atlas rows. */
    public List<AtlasModel.Row> candidates(Player viewer) {
        Location eye = viewer.getLocation();
        List<AtlasModel.Row> rows = new ArrayList<>();
        for (ILocalPortal portal : portals.get()) {
            if (portal == null || portal.isDestroyed() || portal.getStructure() == null
                    || portal.getStructure().getWorld() == null || portal.getDimensionalPortalKind().isManagedPortal()
                    || !portal.canDepart(viewer)) {
                continue;
            }
            rows.add(row(portal, eye));
        }
        return rows;
    }

    private AtlasModel.Row row(ILocalPortal portal, Location eye) {
        Location center = portal.getStructure().getCenter();
        double distanceSquared = center != null && center.getWorld() != null && center.getWorld().equals(eye.getWorld())
                ? center.distanceSquared(eye) : Double.MAX_VALUE;
        String address = "";
        boolean listed = portal.isPublicLookLabel();
        if (portal instanceof LocalPortal local) {
            NexusPortalExtension state = local.extension(NexusPortalExtension.class);
            if (state != null) {
                address = state.address();
                PortalNetwork network = registry.byId(state.networkId());
                listed |= network != null && network.visibility() == Visibility.PUBLIC;
            }
        }
        return new AtlasModel.Row(portal.getId(), portal.getName(), portal.getStructure().getWorld().getName(),
                destinationName(portal), address, distanceSquared, portal.isOpen(), false, listed);
    }

    private static String destinationName(ILocalPortal portal) {
        ITunnel tunnel = portal.getTunnel();
        IPortal destination = tunnel == null ? null : tunnel.getDestination();
        return destination == null ? "" : destination.getName();
    }

    static List<AtlasProximityIndex.Anchor<UUID>> indexAnchors(List<ILocalPortal> portals) {
        List<AtlasProximityIndex.Anchor<UUID>> anchors = new ArrayList<>(portals.size());
        for (ILocalPortal portal : portals) {
            if (portal == null || portal.isDestroyed() || portal.getDimensionalPortalKind().isManagedPortal()) {
                continue;
            }
            Location center = portal.getCenter();
            if (center != null && center.getWorld() != null) {
                anchors.add(new AtlasProximityIndex.Anchor<>(portal.getId(), center.getWorld().getUID(),
                    center.getX(), center.getY(), center.getZ()));
            }
        }
        return anchors;
    }

    private static void recordDeparture(AtlasPlayerState state, UUID portalId, int recentLimit) {
        state.discover(portalId);
        state.recordRecent(portalId, recentLimit);
    }

    private void expirePendingLogins() {
        if (pendingLogins.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        for (Map.Entry<UUID, Long> login : pendingLogins.entrySet()) {
            UUID playerId = login.getKey();
            if (now - login.getValue() >= 0L) {
                synchronized (playersLock) {
                    if (pendingLogins.remove(playerId, login.getValue()) && !online.contains(playerId)) {
                        store.unloadAsync(playerId);
                    }
                }
            }
        }
    }

    private void tickPlayer(Player player, AtlasConfig settings) {
        AtlasPlayerState state = store.cached(player.getUniqueId());
        if (state == null || !player.isOnline()) {
            return;
        }
        Location at = player.getLocation();
        discoverNearby(state, at, settings);
        publishGuide(player, state, at, settings);
    }

    private void discoverNearby(AtlasPlayerState state, Location at, AtlasConfig settings) {
        if (!settings.discoveryRequired || at.getWorld() == null) {
            return;
        }
        List<UUID> near = index.near(at.getWorld().getUID(), at.getX(), at.getY(), at.getZ(), settings.discoveryRadius);
        for (UUID portalId : near) {
            state.discover(portalId);
        }
    }

    private void publishGuide(Player player, AtlasPlayerState state, Location at, AtlasConfig settings) {
        UUID target = state.guideTarget();
        if (!settings.guideEnabled || target == null) {
            return;
        }
        ILocalPortal portal = Wormholes.portalManager == null ? null : Wormholes.portalManager.getLocalPortal(target);
        Location center = portal == null || portal.getStructure() == null ? null : portal.getStructure().getCenter();
        if (center == null || center.getWorld() == null || !center.getWorld().equals(at.getWorld())) {
            return;
        }
        String bearing = AtlasGuide.bearing(at.getYaw(), center.getX() - at.getX(), center.getZ() - at.getZ());
        WormholesHud.guide(player, Wormholes.text().component(player, AtlasMessages.GUIDE_BEARING,
                AtlasText.args("portal", portal.getName(), "value", bearing)));
    }

}
