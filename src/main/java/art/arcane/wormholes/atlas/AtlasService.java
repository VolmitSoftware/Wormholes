package art.arcane.wormholes.atlas;

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
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The runtime behind {@code /atlas}: it discovers portals a player stands at, remembers the ones they
 * travel through, and draws the guide bearing. Discovery probes a chunk-bucketed index, never a scan.
 */
public final class AtlasService implements Listener, TraversalObserver {
    private static final int INDEX_REBUILD_INTERVAL = 5;

    private final AtlasPlayerStore store;
    private final NetworkRegistry registry;
    private final Supplier<AtlasConfig> config;
    private final Supplier<List<ILocalPortal>> portals;
    private final AtlasProximityIndex index = new AtlasProximityIndex();
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
        return store.load(player.getUniqueId());
    }

    @EventHandler
    public void on(PlayerJoinEvent event) {
        store.load(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void on(PlayerQuitEvent event) {
        store.unload(event.getPlayer().getUniqueId());
    }

    @Override
    public void onDeparted(TraversalAttempt attempt) {
        if (!(attempt.traveler() instanceof Player player)) {
            return;
        }
        AtlasPlayerState state = store.cached(player.getUniqueId());
        if (state == null || attempt.portal().getDimensionalPortalKind().isManagedPortal()) {
            return;
        }
        state.discover(attempt.portal().getId());
        state.recordRecent(attempt.portal().getId(), config.get().recentLimit);
    }

    /** One discovery and guide pass over the online players. Runs on the one-second nexus task. */
    public void tick(List<? extends Player> online) {
        AtlasConfig settings = config.get();
        if (!settings.enabled) {
            return;
        }
        if (++ticksSinceRebuild >= INDEX_REBUILD_INTERVAL) {
            ticksSinceRebuild = 0;
            index.rebuild(portals.get());
        }
        for (Player player : online) {
            discoverNearby(player, settings);
            publishGuide(player, settings);
        }
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

    private void discoverNearby(Player player, AtlasConfig settings) {
        if (!settings.discoveryRequired) {
            return;
        }
        AtlasPlayerState state = store.cached(player.getUniqueId());
        if (state == null) {
            return;
        }
        Location at = player.getLocation();
        if (at.getWorld() == null) {
            return;
        }
        List<UUID> near = index.near(at.getWorld().getUID(), at.getX(), at.getY(), at.getZ(), settings.discoveryRadius);
        for (UUID portalId : near) {
            state.discover(portalId);
        }
    }

    private void publishGuide(Player player, AtlasConfig settings) {
        AtlasPlayerState state = store.cached(player.getUniqueId());
        UUID target = state == null ? null : state.guideTarget();
        if (!settings.guideEnabled || target == null) {
            return;
        }
        ILocalPortal portal = Wormholes.portalManager == null ? null : Wormholes.portalManager.getLocalPortal(target);
        Location center = portal == null || portal.getStructure() == null ? null : portal.getStructure().getCenter();
        Location at = player.getLocation();
        if (center == null || center.getWorld() == null || !center.getWorld().equals(at.getWorld())) {
            return;
        }
        String bearing = AtlasGuide.bearing(at.getYaw(), center.getX() - at.getX(), center.getZ() - at.getZ());
        WormholesHud.guide(player, Wormholes.text().component(player, AtlasMessages.GUIDE_BEARING,
                AtlasText.args("portal", portal.getName(), "value", bearing)));
    }

}
