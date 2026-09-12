package art.arcane.wormholes.ops;

import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.api.WormholesApi;
import art.arcane.wormholes.api.destination.DestinationResolver;
import art.arcane.wormholes.api.events.HandoffAdmittedEvent;
import art.arcane.wormholes.api.events.HandoffCompletedEvent;
import art.arcane.wormholes.api.events.HandoffDeniedEvent;
import art.arcane.wormholes.api.events.PeerConnectedEvent;
import art.arcane.wormholes.api.events.PeerDisconnectedEvent;
import art.arcane.wormholes.api.events.PortalCreatedEvent;
import art.arcane.wormholes.api.events.PortalDestroyedEvent;
import art.arcane.wormholes.api.events.PortalLinkedEvent;
import art.arcane.wormholes.api.network.NetworkQuery;
import art.arcane.wormholes.api.network.PeerSnapshot;
import art.arcane.wormholes.api.portal.PortalMutations;
import art.arcane.wormholes.api.portal.PortalQuery;
import art.arcane.wormholes.api.portal.PortalSnapshot;
import art.arcane.wormholes.hook.TraversalAttempt;
import art.arcane.wormholes.hook.TraversalObserver;
import art.arcane.wormholes.hook.TraversalVerdict;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.RemotePortalRegistry;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.RemotePortal;
import art.arcane.wormholes.portal.UniversalTunnel;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.event.Event;
import org.bukkit.plugin.ServicePriority;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The public API implementation. It publishes a 1 Hz portal and peer snapshot, fires the lifecycle
 * events from the differences, adapts public destination resolvers onto the internal traversal hook,
 * and hands out the mutation facade.
 *
 * <p>{@code RtpDestinationChosenEvent} and {@code DoorTransitEvent} are published types this bridge
 * does not fire: the RTP and door paths expose no public callback yet.</p>
 */
public final class ApiBridge implements WormholesApi, TraversalObserver, NetworkQuery {
    private final SnapshotPortalQuery portals = new SnapshotPortalQuery();
    private final ApiMutations mutations = new ApiMutations();
    private final List<DestinationResolver> resolvers = new CopyOnWriteArrayList<>();

    private Map<UUID, PortalSnapshot> lastPortals = Map.of();
    private Map<String, PeerSnapshot> lastPeers = Map.of();

    void register(WormholesRegistrar registrar) {
        registrar.destinationResolver(new ApiDestinationResolvers(resolvers, portals));
        registrar.traversalObserver(this);
    }

    void start(Wormholes plugin) {
        Bukkit.getServicesManager().register(WormholesApi.class, this, plugin, ServicePriority.Normal);
    }

    void stop() {
        Bukkit.getServicesManager().unregister(WormholesApi.class, this);
        resolvers.clear();
        portals.publish(List.of());
        lastPortals = Map.of();
        lastPeers = Map.of();
    }

    /** One poll: republish the snapshot and fire whatever changed. Runs on the global region. */
    void tick() {
        List<PortalSnapshot> current = localSnapshots();
        portals.publish(current);

        ApiSnapshotDiff.Change change = ApiSnapshotDiff.portals(lastPortals, current);
        Map<UUID, PortalSnapshot> nextPortals = new LinkedHashMap<>();
        for (PortalSnapshot snapshot : current) {
            nextPortals.put(snapshot.id(), snapshot);
        }
        lastPortals = Map.copyOf(nextPortals);

        if (PortalCreatedEvent.listening()) {
            for (PortalSnapshot created : change.created()) {
                call(new PortalCreatedEvent(created));
            }
        }
        if (PortalLinkedEvent.listening()) {
            for (PortalSnapshot linked : change.linked()) {
                call(new PortalLinkedEvent(linked));
            }
        }
        if (PortalDestroyedEvent.listening()) {
            for (PortalSnapshot destroyed : change.destroyed()) {
                call(new PortalDestroyedEvent(destroyed.id(), destroyed.name()));
            }
        }

        List<PeerSnapshot> peers = peers();
        ApiSnapshotDiff.PeerChange peerChange = ApiSnapshotDiff.peers(lastPeers, peers);
        Map<String, PeerSnapshot> nextPeers = new LinkedHashMap<>();
        for (PeerSnapshot peer : peers) {
            nextPeers.put(peer.name(), peer);
        }
        lastPeers = Map.copyOf(nextPeers);

        if (PeerConnectedEvent.listening()) {
            for (PeerSnapshot connected : peerChange.connected()) {
                call(new PeerConnectedEvent(connected));
            }
        }
        if (PeerDisconnectedEvent.listening()) {
            for (String name : peerChange.disconnected()) {
                call(new PeerDisconnectedEvent(name));
            }
        }
    }

    @Override
    public PortalQuery portals() {
        return portals;
    }

    @Override
    public NetworkQuery network() {
        return this;
    }

    @Override
    public PortalMutations mutations() {
        return mutations;
    }

    @Override
    public void registerDestinationResolver(DestinationResolver resolver) {
        if (resolver != null) {
            resolvers.add(resolver);
        }
    }

    @Override
    public String localName() {
        NetworkManager network = Wormholes.networkManager;
        return network == null ? "" : network.getLocalName();
    }

    @Override
    public List<PeerSnapshot> peers() {
        NetworkManager network = Wormholes.networkManager;
        if (network == null) {
            return List.of();
        }
        RemotePortalRegistry registry = Wormholes.remotePortalRegistry;
        List<PeerSnapshot> peers = new ArrayList<>();
        for (NetworkManager.PeerSnapshot peer : network.peerSnapshots()) {
            peers.add(new PeerSnapshot(peer.name(), peer.transport(),
                peer.handshakeComplete() && !peer.disconnected(), peer.rttMillis(),
                registry == null ? 0 : countRemote(registry, peer.name())));
        }
        return List.copyOf(peers);
    }

    @Override
    public Optional<PeerSnapshot> peer(String name) {
        for (PeerSnapshot peer : peers()) {
            if (peer.name().equalsIgnoreCase(name)) {
                return Optional.of(peer);
            }
        }
        return Optional.empty();
    }

    @Override
    public List<PortalSnapshot> remotePortals() {
        RemotePortalRegistry registry = Wormholes.remotePortalRegistry;
        if (registry == null) {
            return List.of();
        }
        List<PortalSnapshot> snapshots = new ArrayList<>();
        for (RemotePortal portal : registry.all()) {
            snapshots.add(remoteSnapshot(portal));
        }
        return List.copyOf(snapshots);
    }

    @Override
    public Optional<PortalSnapshot> remotePortal(UUID id) {
        for (PortalSnapshot snapshot : remotePortals()) {
            if (snapshot.id().equals(id)) {
                return Optional.of(snapshot);
            }
        }
        return Optional.empty();
    }

    @Override
    public void onDeparted(TraversalAttempt attempt) {
        if (!HandoffAdmittedEvent.listening()) {
            return;
        }
        ITunnel tunnel = attempt.tunnel();
        String server = tunnel instanceof UniversalTunnel universal ? universal.getServerName() : null;
        call(new HandoffAdmittedEvent(attempt.traveler().getUniqueId(), attempt.portal().getId(), server));
    }

    @Override
    public void onArrived(LocalPortal destination, Entity traveler, Location arrival) {
        if (HandoffCompletedEvent.listening()) {
            call(new HandoffCompletedEvent(traveler.getUniqueId(), destination.getId()));
        }
    }

    @Override
    public void onRejected(TraversalAttempt attempt, TraversalVerdict.Deny verdict) {
        if (HandoffDeniedEvent.listening()) {
            call(new HandoffDeniedEvent(attempt.traveler().getUniqueId(), attempt.portal().getId(),
                verdict.reason().id()));
        }
    }

    private static int countRemote(RemotePortalRegistry registry, String peerName) {
        int count = 0;
        for (RemotePortal portal : registry.all()) {
            if (portal.getServer() != null && peerName.equals(portal.getServer().getName())) {
                count++;
            }
        }
        return count;
    }

    private static List<PortalSnapshot> localSnapshots() {
        PortalManager manager = Wormholes.portalManager;
        if (manager == null) {
            return List.of();
        }
        List<PortalSnapshot> snapshots = new ArrayList<>();
        for (ILocalPortal portal : manager.getLocalPortals()) {
            snapshots.add(localSnapshot(portal));
        }
        return List.copyOf(snapshots);
    }

    private static PortalSnapshot localSnapshot(ILocalPortal portal) {
        Location center = portal.getCenter();
        ITunnel tunnel = portal.getTunnel();
        UUID destinationId = null;
        String destinationServer = null;
        if (tunnel instanceof UniversalTunnel universal) {
            destinationId = universal.getDestinationPortalId();
            destinationServer = universal.getServerName();
        } else if (tunnel != null) {
            IPortal destination = tunnel.getDestination();
            destinationId = destination == null ? tunnel.getDestinationId() : destination.getId();
        }
        return new PortalSnapshot(portal.getId(), portal.getName(), portal.getType().name(),
            center == null || center.getWorld() == null ? "" : WorldIdentity.serialize(center.getWorld()),
            center == null ? 0.0D : center.getX(), center == null ? 0.0D : center.getY(),
            center == null ? 0.0D : center.getZ(),
            portal.getFrame() == null ? "N" : portal.getFrame().getNormal().name(),
            portal.isOpen(), destinationId, destinationServer,
            portal instanceof LocalPortal local ? local.getOwner() : null, true);
    }

    private static PortalSnapshot remoteSnapshot(RemotePortal portal) {
        return new PortalSnapshot(portal.getId(), portal.getName(), portal.getType().name(), "",
            portal.getOrigin().getX(), portal.getOrigin().getY(), portal.getOrigin().getZ(),
            portal.getDirection() == null ? "N" : portal.getDirection().name(), portal.isOpen(), null,
            portal.getServer() == null ? null : portal.getServer().getName(), null, true);
    }

    private static void call(Event event) {
        Wormholes plugin = Wormholes.instance;
        if (plugin == null) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getPluginManager().callEvent(event);
            return;
        }
        FoliaScheduler.runGlobal(plugin, () -> Bukkit.getPluginManager().callEvent(event));
    }
}
