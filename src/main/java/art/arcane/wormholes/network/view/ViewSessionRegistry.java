package art.arcane.wormholes.network.view;

import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.replication.ChunkReplicationManager;
import art.arcane.wormholes.portal.ILocalPortal;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class ViewSessionRegistry {
    private final NetworkManager network;
    private final Map<UUID, ViewSession> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean active = new AtomicBoolean(true);
    private volatile Consumer<ViewSession> retirementListener = session -> {
    };

    ViewSessionRegistry(NetworkManager network) {
        this.network = network;
    }

    NetworkManager network() {
        return network;
    }

    ChunkReplicationManager replication() {
        return network.getReplicationManager();
    }

    boolean isActive() {
        return active.get();
    }

    void deactivate() {
        active.set(false);
    }

    void setRetirementListener(Consumer<ViewSession> listener) {
        retirementListener = Objects.requireNonNull(listener, "listener");
    }

    ViewSession get(UUID portalId) {
        return sessions.get(portalId);
    }

    Collection<ViewSession> sessions() {
        return sessions.values();
    }

    boolean isEmpty() {
        return sessions.isEmpty();
    }

    int size() {
        return sessions.size();
    }

    void clear() {
        for (Map.Entry<UUID, ViewSession> entry : sessions.entrySet()) {
            if (sessions.remove(entry.getKey(), entry.getValue())) {
                retirementListener.accept(entry.getValue());
            }
        }
    }

    boolean remove(UUID portalId, ViewSession session) {
        if (!sessions.remove(portalId, session)) {
            return false;
        }
        retirementListener.accept(session);
        return true;
    }

    void remove(UUID portalId) {
        ViewSession removed = sessions.remove(portalId);
        if (removed != null) {
            retirementListener.accept(removed);
        }
    }

    ViewSession openSession(ILocalPortal portal) {
        return sessions.computeIfAbsent(portal.getId(), id -> new ViewSession(
            id,
            portal.getStructure().getWorld(),
            ViewServer.computeBox(portal, portal.getNetworkViewDepth()),
            portal.getRenderMode(),
            ((int) Math.floor(portal.getOrigin().getX())) >> 4,
            ((int) Math.floor(portal.getOrigin().getZ())) >> 4,
            portal.getOrigin().getX(),
            portal.getOrigin().getY(),
            portal.getOrigin().getZ()
        ));
    }

    boolean isSessionPeerActive(ViewSession session, String peerName) {
        return active.get() && sessions.get(session.portalId) == session && session.peers.contains(peerName);
    }

    boolean isSessionChunkActive(ViewSession session, String peerName, long chunkKey) {
        return isSessionPeerActive(session, peerName)
            && network.getReplicationManager().isSubscribed(peerName, session.subscriptionId, session.streamFor(chunkKey));
    }

    boolean isSessionCurrent(ViewSession session) {
        return active.get() && sessions.get(session.portalId) == session;
    }

    boolean isEntityCaptureActive(ViewSession session, ViewServer.EntityCaptureToken token) {
        return active.get()
            && sessions.get(session.portalId) == session
            && session.activeEntityCapture == token
            && session.entityCaptureGeneration.get() == token.generation()
            && token.isActive();
    }

    void unsubscribeSessionReplication(ViewSession session) {
        ChunkReplicationManager replication = network.getReplicationManager();
        for (String peerName : session.peers) {
            replication.unsubscribeAll(peerName, session.subscriptionId, session.streamKeys);
        }
    }
}
