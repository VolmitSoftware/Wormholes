package art.arcane.wormholes.network;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

final class PeerLinkRegistry {
    static final int MAX_PENDING_INBOUND_HANDSHAKES = 128;

    private final Map<String, PeerConnection> readyPeers = new ConcurrentHashMap<>();
    private final Set<PeerConnection> pending = ConcurrentHashMap.newKeySet();
    private final Set<PeerConnection> pendingInbound = ConcurrentHashMap.newKeySet();
    private final Object pendingInboundGate = new Object();

    void addPending(PeerConnection connection) {
        pending.add(connection);
    }

    boolean tryAddInboundPending(PeerConnection connection) {
        synchronized (pendingInboundGate) {
            if (pendingInbound.size() >= MAX_PENDING_INBOUND_HANDSHAKES || !pending.add(connection)) {
                return false;
            }
            if (pendingInbound.add(connection)) {
                return true;
            }
            pending.remove(connection);
            return false;
        }
    }

    void removePending(PeerConnection connection) {
        pending.remove(connection);
        synchronized (pendingInboundGate) {
            pendingInbound.remove(connection);
        }
    }

    int pendingCount() {
        return pending.size();
    }

    int pendingInboundCount() {
        return pendingInbound.size();
    }

    boolean hasPendingDial(String peerName) {
        for (PeerConnection connection : pending) {
            if (connection.isDialer() && peerName.equals(connection.getPeerName())) {
                return true;
            }
        }
        return false;
    }

    PeerConnection ready(String name) {
        return readyPeers.get(name);
    }

    boolean containsReady(String name) {
        return readyPeers.containsKey(name);
    }

    boolean isRawReady(String name) {
        PeerConnection connection = readyPeers.get(name);
        return connection != null && connection.getState() == PeerConnection.State.READY;
    }

    Collection<PeerConnection> readyConnections() {
        return readyPeers.values();
    }

    Set<Map.Entry<String, PeerConnection>> readyEntries() {
        return readyPeers.entrySet();
    }

    int readyCount() {
        return readyPeers.size();
    }

    boolean removeReady(String name, PeerConnection connection) {
        return readyPeers.remove(name, connection);
    }

    boolean promote(String name, PeerConnection connection, Function<PeerConnection, String> initiatorName) {
        PeerConnection displaced = null;
        synchronized (readyPeers) {
            PeerConnection previous = readyPeers.get(name);
            if (previous != null && previous != connection && previous.getState() == PeerConnection.State.READY) {
                if (initiatorName.apply(previous).compareTo(initiatorName.apply(connection)) <= 0) {
                    connection.close("duplicate connection (kept " + initiatorName.apply(previous) + "-initiated)");
                    return false;
                }
                displaced = previous;
            }
            readyPeers.put(name, connection);
        }
        if (displaced != null) {
            displaced.close("duplicate connection (kept " + initiatorName.apply(connection) + "-initiated)");
        }
        return true;
    }

    long writeQueueFrames() {
        Set<PeerConnection> rawConnections = new HashSet<>(pending);
        rawConnections.addAll(readyPeers.values());
        long frames = 0L;
        for (PeerConnection connection : rawConnections) {
            frames += connection.getWriteQueueSize();
        }
        return frames;
    }

    void closeAll(String reason) {
        Set<PeerConnection> connections = new HashSet<>(pending);
        connections.addAll(readyPeers.values());
        for (PeerConnection connection : connections) {
            connection.close(reason);
        }
        synchronized (pendingInboundGate) {
            pending.clear();
            pendingInbound.clear();
        }
        readyPeers.clear();
    }
}
