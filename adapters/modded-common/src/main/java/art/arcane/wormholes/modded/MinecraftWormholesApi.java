package art.arcane.wormholes.modded;

import art.arcane.wormholes.api.destination.DestinationResolver;
import art.arcane.wormholes.api.network.NetworkQuery;
import art.arcane.wormholes.api.network.PeerSnapshot;
import art.arcane.wormholes.api.portal.PortalQuery;
import art.arcane.wormholes.api.portal.PortalSnapshot;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.nexus.NetworkMember;
import art.arcane.wormholes.ops.ApiSnapshotDiff;
import art.arcane.wormholes.ops.SnapshotPortalQuery;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.RemotePortal;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class MinecraftWormholesApi implements NetworkQuery, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final Map<MinecraftServer, MinecraftWormholesApi> SERVICES = new ConcurrentHashMap<>();
    private final WormholesModRuntime runtime;
    private final SnapshotPortalQuery portals = new SnapshotPortalQuery();
    private final List<DestinationResolver> resolvers = new CopyOnWriteArrayList<>();
    private final List<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();
    private final Map<CompletableFuture<?>, Boolean> pending = new ConcurrentHashMap<>();
    private Map<UUID, PortalSnapshot> previousPortals = Map.of();
    private Map<String, PeerSnapshot> previousPeers = Map.of();
    private volatile List<PeerSnapshot> peers = List.of();
    private volatile List<PortalSnapshot> remote = List.of();
    private volatile String localName = "";
    private volatile boolean active;
    private int ticks;

    public MinecraftWormholesApi(WormholesModRuntime runtime) {
        this.runtime = runtime;
    }

    public static Optional<MinecraftWormholesApi> forServer(MinecraftServer server) {
        return Optional.ofNullable(SERVICES.get(server));
    }

    public void start() {
        runtime.requireServerThread();
        active = true;
        SERVICES.put(runtime.server(), this);
        refresh();
    }

    public void tick() {
        if (active && ++ticks >= 20) {
            ticks = 0;
            refresh();
        }
    }

    @Override
    public void close() {
        active = false;
        SERVICES.values().removeIf(service -> service == this);
        for (CompletableFuture<?> future : pending.keySet()) {
            future.completeExceptionally(new IllegalStateException("Wormholes stopped"));
        }
        pending.clear();
        resolvers.clear();
        listeners.clear();
        portals.publish(List.of());
        previousPortals = Map.of();
        previousPeers = Map.of();
        peers = List.of();
        remote = List.of();
        localName = "";
        ticks = 0;
    }

    public PortalQuery portals() { return portals; }
    public NetworkQuery network() { return this; }
    @Override public String localName() { return localName; }
    @Override public List<PeerSnapshot> peers() { return peers; }
    @Override public Optional<PeerSnapshot> peer(String name) { return peers.stream().filter(peer -> peer.name().equalsIgnoreCase(name)).findFirst(); }
    @Override public List<PortalSnapshot> remotePortals() { return remote; }
    @Override public Optional<PortalSnapshot> remotePortal(UUID id) { return remote.stream().filter(portal -> portal.id().equals(id)).findFirst(); }

    public AutoCloseable registerDestinationResolver(DestinationResolver resolver) {
        resolvers.add(Objects.requireNonNull(resolver));
        return () -> resolvers.remove(resolver);
    }

    public AutoCloseable listen(Consumer<Event> listener) {
        listeners.add(Objects.requireNonNull(listener));
        return () -> listeners.remove(listener);
    }

    public CompletableFuture<UUID> create(ServerLevel level, Collection<BlockPos> cells, String name, UUID owner) {
        List<BlockPos> positions = cells.stream().map(BlockPos::immutable).toList();
        return submit(() -> {
            MinecraftPortal portal = runtime.portals().create(owner, level, positions, PortalType.PORTAL, new Vec3(0, 0, -1));
            portal.setName(name);
            runtime.portals().save(portal);
            return portal.getId();
        });
    }

    public CompletableFuture<Boolean> link(UUID sourceId, UUID destinationId) {
        return submit(() -> {
            MinecraftPortal source = runtime.portals().get(sourceId);
            MinecraftPortal destination = runtime.portals().get(destinationId);
            if (source == null || destination == null || source.isMirrorMode() || source.getType() == PortalType.RTP || destination.getType() == PortalType.RTP) {
                return false;
            }
            source.link(destination);
            runtime.portals().save(source);
            return true;
        });
    }

    public CompletableFuture<Boolean> unlink(UUID portalId) {
        return submit(() -> {
            MinecraftPortal portal = runtime.portals().get(portalId);
            if (portal == null) { return false; }
            portal.unlink();
            runtime.portals().save(portal);
            return true;
        });
    }

    public CompletableFuture<Boolean> destroy(UUID portalId) { return submit(() -> runtime.portals().remove(portalId)); }

    public CompletableFuture<Boolean> rename(UUID portalId, String name) {
        return submit(() -> {
            MinecraftPortal portal = runtime.portals().get(portalId);
            if (portal == null) { return false; }
            portal.setName(name);
            runtime.portals().save(portal);
            return true;
        });
    }

    public NetworkMember resolve(MinecraftPortal source, UUID traveler) {
        for (DestinationResolver resolver : resolvers) {
            try {
                Optional<UUID> destination = resolver.resolve(snapshot(source), traveler);
                if (destination.isEmpty()) { continue; }
                MinecraftPortal local = runtime.portals().get(destination.get());
                if (local != null && local.isOpen()) {
                    return new NetworkMember(local.getId(), "", "", 0, "");
                }
                for (PortalSnapshot candidate : remote) {
                    if (candidate.id().equals(destination.get()) && candidate.open()) {
                        return new NetworkMember(candidate.id(), "", "", 0, candidate.destinationServer());
                    }
                }
            } catch (RuntimeException error) {
                LOGGER.error("Wormholes destination resolver failed", error);
            }
        }
        return null;
    }

    public boolean hasResolvers() { return !resolvers.isEmpty(); }

    public void emit(Event event) {
        for (Consumer<Event> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException error) {
                LOGGER.error("Wormholes API event listener failed for {}", event.kind(), error);
            }
        }
    }

    private void refresh() {
        List<PortalSnapshot> current = new ArrayList<>();
        for (MinecraftPortal portal : runtime.portals().snapshot()) { current.add(snapshot(portal)); }
        portals.publish(current);
        ApiSnapshotDiff.Change changes = ApiSnapshotDiff.portals(previousPortals, current);
        Map<UUID, PortalSnapshot> next = new LinkedHashMap<>();
        for (PortalSnapshot portal : current) { next.put(portal.id(), portal); }
        previousPortals = Map.copyOf(next);
        for (PortalSnapshot portal : changes.created()) { emit(new Event(Kind.PORTAL_CREATED, null, portal.id(), null, "", portal, null)); }
        for (PortalSnapshot portal : changes.linked()) { emit(new Event(Kind.PORTAL_LINKED, null, portal.id(), portal.destinationServer(), "", portal, null)); }
        for (PortalSnapshot portal : changes.destroyed()) { emit(new Event(Kind.PORTAL_DESTROYED, null, portal.id(), null, portal.name(), portal, null)); }
        NetworkManager network = runtime.network().manager();
        localName = network.getLocalName();
        List<PortalSnapshot> remotes = new ArrayList<>();
        for (RemotePortal portal : runtime.network().remotePortals().all()) {
            remotes.add(new PortalSnapshot(portal.getId(), portal.getName(), portal.getType().name(), "",
                portal.getOrigin().getX(), portal.getOrigin().getY(), portal.getOrigin().getZ(), portal.getDirection().name(),
                portal.isOpen(), null, portal.getServer() == null ? null : portal.getServer().getName(), null, true));
        }
        remote = List.copyOf(remotes);
        List<PeerSnapshot> currentPeers = new ArrayList<>();
        for (NetworkManager.PeerSnapshot peer : network.peerSnapshots()) {
            int count = 0;
            for (PortalSnapshot portal : remote) { if (peer.name().equals(portal.destinationServer())) { count++; } }
            currentPeers.add(new PeerSnapshot(peer.name(), peer.transport(), peer.handshakeComplete() && !peer.disconnected(), peer.rttMillis(), count));
        }
        peers = List.copyOf(currentPeers);
        ApiSnapshotDiff.PeerChange peerChanges = ApiSnapshotDiff.peers(previousPeers, peers);
        Map<String, PeerSnapshot> nextPeers = new LinkedHashMap<>();
        for (PeerSnapshot peer : peers) { nextPeers.put(peer.name(), peer); }
        previousPeers = Map.copyOf(nextPeers);
        for (PeerSnapshot peer : peerChanges.connected()) { emit(new Event(Kind.PEER_CONNECTED, null, null, peer.name(), "", null, peer)); }
        for (String peer : peerChanges.disconnected()) { emit(new Event(Kind.PEER_DISCONNECTED, null, null, peer, "", null, null)); }
    }

    private <T> CompletableFuture<T> submit(Supplier<T> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        pending.put(result, true);
        if (!active || !runtime.schedule(() -> {
            try {
                if (!result.isDone() && active) { result.complete(operation.get()); }
            }
            catch (RuntimeException error) { result.completeExceptionally(error); }
            finally { pending.remove(result); }
        }, 1L)) {
            pending.remove(result);
            result.completeExceptionally(new IllegalStateException("Wormholes is not running"));
        }
        return result;
    }

    private static PortalSnapshot snapshot(MinecraftPortal portal) {
        return new PortalSnapshot(portal.getId(), portal.getName(), portal.getType().name(), portal.getWorldKey(),
            portal.getOrigin().getX(), portal.getOrigin().getY(), portal.getOrigin().getZ(), portal.getFrame().getNormal().name(),
            portal.isOpen(), portal.getDestinationId(), portal.getDestinationServer(), portal.getOwner(), Boolean.TRUE.equals(portal.setting("publicLookLabel")));
    }

    public enum Kind { PORTAL_CREATED, PORTAL_LINKED, PORTAL_DESTROYED, PEER_CONNECTED, PEER_DISCONNECTED, HANDOFF_ADMITTED, HANDOFF_DENIED, HANDOFF_COMPLETED }
    public record Event(Kind kind, UUID travelerId, UUID portalId, String server, String detail, PortalSnapshot portal, PeerSnapshot peer) { }
}
