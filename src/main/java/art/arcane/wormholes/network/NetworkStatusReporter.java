package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class NetworkStatusReporter {
    private final NetworkManager network;

    NetworkStatusReporter(NetworkManager network) {
        this.network = network;
    }

    List<NetworkManager.PeerStatus> status() {
        List<NetworkConfig.PeerEntry> peers = network.directory().known();
        PeerLinkRegistry links = network.links();
        SidebandPresence presence = network.presence();
        List<NetworkManager.PeerStatus> statuses = new ArrayList<>(peers.size() + links.readyCount());
        long now = System.currentTimeMillis();
        for (NetworkConfig.PeerEntry peer : peers) {
            PeerConnection connection = links.ready(peer.name);
            if (connection != null && connection.getState() == PeerConnection.State.READY) {
                statuses.add(new NetworkManager.PeerStatus(peer.name, connection.describeRemote(), "CONNECTED", connection.isDialer(), connection.getRttMillis(), now - connection.getLastInboundMillis(), null));
            } else if (network.isStatusPeerReady(peer.name)) {
                statuses.add(new NetworkManager.PeerStatus(peer.name, PeerDirectory.statusBridgeAddress(peer), "CONNECTED", PeerDirectory.canUseStatusBridge(peer), presence.rttOrDefault(peer.name, -1L), now - presence.lastSeenOrDefault(peer.name, now), null));
            } else if (!PeerDirectory.isDialable(peer)) {
                statuses.add(new NetworkManager.PeerStatus(peer.name, PeerDirectory.peerAddress(peer), network.isRunning() ? "WAITING" : "OFFLINE", false, -1L, -1L, "no dial address; waiting for the peer to reach this server"));
            } else {
                statuses.add(new NetworkManager.PeerStatus(peer.name, PeerDirectory.peerAddress(peer), network.isRunning() ? "CONNECTING" : "OFFLINE", false, -1L, -1L, network.dialer().lastError(peer.name)));
            }
        }
        for (Map.Entry<String, PeerConnection> entry : links.readyEntries()) {
            if (network.directory().find(entry.getKey()) != null) {
                continue;
            }
            PeerConnection connection = entry.getValue();
            if (connection.getState() == PeerConnection.State.READY) {
                statuses.add(new NetworkManager.PeerStatus(entry.getKey(), connection.describeRemote(), "CONNECTED", connection.isDialer(), connection.getRttMillis(), now - connection.getLastInboundMillis(), null));
            }
        }
        return statuses;
    }

    List<NetworkManager.PeerSnapshot> peerSnapshots() {
        long now = System.currentTimeMillis();
        PeerLinkRegistry links = network.links();
        SidebandPresence presence = network.presence();
        Map<String, Long> lastSeen = presence.lastSeenView();
        List<NetworkManager.PeerSnapshot> result = new ArrayList<>(links.readyCount() + lastSeen.size());
        for (PeerConnection connection : links.readyConnections()) {
            String name = connection.getPeerName();
            if (name == null) {
                continue;
            }
            boolean connected = connection.getState() == PeerConnection.State.READY;
            boolean disconnected = connection.getState() == PeerConnection.State.CLOSED;
            String remote = connection.describeRemote();
            String transport = remote != null && remote.startsWith("uds:") ? "UDS" : "TCP";
            String mode = compressionModeFor(connection);
            int dictVersion = connection.getNegotiatedDictVersion();
            String dictHashHex = compressionDictHashHex(connection);
            result.add(new NetworkManager.PeerSnapshot(
                name,
                transport,
                remote,
                mode,
                dictVersion,
                dictHashHex,
                connected ? Math.max(0L, now - connection.getLastInboundMillis()) : -1L,
                connected ? connection.getRttMillis() : -1L,
                connected,
                disconnected
            ));
        }
        for (Map.Entry<String, Long> entry : lastSeen.entrySet()) {
            String name = entry.getKey();
            if (links.containsReady(name)) {
                continue;
            }
            NetworkConfig.PeerEntry peer = network.directory().find(name);
            String remote = peer == null ? "game-port sideband" : PeerDirectory.statusBridgeAddress(peer);
            long rtt = presence.rttOrDefault(name, -1L);
            result.add(new NetworkManager.PeerSnapshot(
                name,
                "SIDEBAND",
                remote,
                "none",
                0,
                "-",
                Math.max(0L, now - entry.getValue()),
                rtt,
                true,
                false
            ));
        }
        for (NetworkConfig.PeerEntry peer : network.directory().all()) {
            if (peer.name == null || peer.name.isBlank()) {
                continue;
            }
            if (links.containsReady(peer.name) || presence.isTracked(peer.name)) {
                continue;
            }
            result.add(new NetworkManager.PeerSnapshot(
                peer.name,
                "TCP",
                PeerDirectory.peerAddress(peer),
                "pending",
                0,
                "-",
                -1L,
                -1L,
                false,
                false
            ));
        }
        result.sort((a, b) -> a.name().compareTo(b.name()));
        return result;
    }

    NetworkManager.DebugSnapshot debugSnapshot() {
        SidebandQueue.Totals totals = network.sideband().totals();
        return new NetworkManager.DebugSnapshot(
            network.links().writeQueueFrames(),
            totals.queuedBytes(),
            totals.queuedCount(),
            totals.droppedBytes(),
            totals.droppedCount()
        );
    }

    List<String> diagnostics() {
        NetworkConfig active = network.activeConfig();
        List<String> messages = new ArrayList<>();
        if (!active.enabled) {
            messages.add("Networking is disabled.");
            return messages;
        }
        int bound = network.listener().boundPort();
        int gamePort = network.gamePort();
        if (active.listenEnabled) {
            if (bound <= 0) {
                messages.add("Raw Wormholes listener is unbound; running sideband-only over the MC game port " + gamePort + ". Open any port in " + active.listenPort + ".." + (active.listenPort + PeerListener.LISTEN_PORT_FALLBACK_RANGE) + " to enable high-throughput projection streaming.");
            } else if (bound == gamePort) {
                messages.add("Wormholes listen-port resolved to the Minecraft game port " + gamePort + "; the raw listener cannot bind the existing game socket. Peers use the signed status sideband instead.");
            } else {
                messages.add("Raw Wormholes peers dial " + bound + ". If that port is not reachable, imported peers with public-host/public-port can still exchange small signed control frames over the Minecraft game port " + gamePort + "; open the raw port for high-throughput projection streaming.");
            }
        } else {
            messages.add("This server is outbound-only. It will not accept inbound raw Wormholes sockets; it relies on dialed peers or the game-port status sideband.");
        }
        for (NetworkConfig.PeerEntry peer : network.directory().known()) {
            if (peer.name == null || peer.name.isBlank() || network.isPeerReady(peer.name)) {
                continue;
            }
            String publicAddress = peer.publicHost == null || peer.publicHost.isBlank() ? "no player address" : peer.publicHost + ":" + peer.publicPort;
            messages.add("Peer " + peer.name + " is dialed at raw address " + PeerDirectory.peerAddress(peer) + "; its player join address " + publicAddress + " is used as a signed status sideband when the raw port is unreachable.");
        }
        return messages;
    }

    private static String compressionModeFor(PeerConnection connection) {
        if (connection.getState() != PeerConnection.State.READY) {
            return "pending";
        }
        if (!connection.isPeerCompressionSupported()) {
            return "none";
        }
        if (connection.isDictionaryNegotiated()) {
            return "dict";
        }
        return "dictless";
    }

    private String compressionDictHashHex(PeerConnection connection) {
        if (!connection.isDictionaryNegotiated()) {
            return "-";
        }
        CompressionDictionary local = network.currentDictionary();
        if (local == null) {
            return "-";
        }
        return local.hashHex8();
    }
}
