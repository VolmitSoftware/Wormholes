package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;

import java.io.IOException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

final class RelayRouter {
    static final int ROUTE_TTL = 8;

    private final NetworkManager network;
    private final Logger logger;
    private final Map<String, String> routes = new ConcurrentHashMap<>();
    private final Map<String, RelayedPortalAnnouncements> relayedPortalAnnouncements = new ConcurrentHashMap<>();

    RelayRouter(NetworkManager network, Logger logger) {
        this.network = network;
        this.logger = logger;
    }

    String nextHop(String targetServer) {
        return routes.get(targetServer);
    }

    void forgetVia(String peerName) {
        routes.entrySet().removeIf(entry -> entry.getValue().equals(peerName));
    }

    boolean handleRouted(String inboundPeer, WireMessage.Routed routed) {
        if (routed.sourceServer() == null || routed.sourceServer().isBlank() || routed.targetServer() == null || routed.targetServer().isBlank()) {
            return false;
        }
        if (routed.sourceServer().equals(network.getLocalName())) {
            return false;
        }
        PublicKey trustedKey = network.trust().publicKey(routed.sourceServer());
        if (trustedKey == null || !routed.authenticates(trustedKey)) {
            logger.warning("net: rejected routed message claiming origin " + routed.sourceServer() + " via " + inboundPeer + " because origin authentication failed");
            return false;
        }
        learnRoute(routed.sourceServer(), inboundPeer);
        if (routed.targetServer().equals(network.getLocalName())) {
            try {
                WireMessage inner = WireCodec.decodePayload(routed.innerType(), routed.payload());
                cacheAnnouncement(routed, inner);
                relayAnnouncement(routed, inboundPeer, inner);
                network.deliverMessage(routed.sourceServer(), inner);
            } catch (IOException e) {
                logger.warning("net: dropped routed message from " + routed.sourceServer() + ": " + e.getMessage());
            }
            return true;
        }
        if (routed.ttl() <= 0) {
            return true;
        }
        return forwardRouted(inboundPeer, routed.withTtl(routed.ttl() - 1));
    }

    boolean sendRouted(PeerConnection connection, String targetServer, int ttl, WireMessage message) {
        if (message instanceof WireMessage.Routed) {
            return false;
        }
        try {
            return connection.send(createRouted(targetServer, ttl, message));
        } catch (IOException e) {
            logger.warning("net: could not route " + message.type() + " from " + network.getLocalName() + " to " + targetServer + ": " + e.getMessage());
            return false;
        }
    }

    boolean enqueueRouted(String nextHop, String targetServer, int ttl, WireMessage message) {
        if (message instanceof WireMessage.Routed) {
            return false;
        }
        try {
            WireMessage.Routed routed = createRouted(targetServer, ttl, message);
            return enqueueForwarded(nextHop, routed);
        } catch (IOException e) {
            logger.warning("net: could not queue routed status sideband " + message.type() + " from " + network.getLocalName() + " to " + targetServer + ": " + e.getMessage());
            return false;
        }
    }

    WireMessage.Routed createRouted(String targetServer, int ttl, WireMessage message) throws IOException {
        return WireMessage.Routed.sign(network.getLocalName(), targetServer, ttl, message, network.identityPrivateKey());
    }

    private void cacheAnnouncement(WireMessage.Routed routed, WireMessage message) {
        String sourceServer = routed.sourceServer();
        if (sourceServer == null || sourceServer.isBlank() || !WireMessage.Routed.isRelayAnnouncement(message.type())) {
            return;
        }
        RelayedPortalAnnouncements announcements = relayedPortalAnnouncements.computeIfAbsent(
            sourceServer,
            ignored -> new RelayedPortalAnnouncements()
        );
        announcements.record(routed, message);
    }

    private void relayAnnouncement(WireMessage.Routed routed, String inboundPeer, WireMessage message) {
        int ttl = routed.ttl() - 1;
        if (ttl <= 0 || !WireMessage.Routed.isRelayAnnouncement(message.type())) {
            return;
        }
        String sourceServer = routed.sourceServer();
        for (NetworkConfig.PeerEntry peer : network.directory().known()) {
            String target = peer.name;
            if (target.equals(inboundPeer) || target.equals(sourceServer) || target.equals(network.getLocalName())) {
                continue;
            }
            WireMessage.Routed forwarded = routed.withTarget(target).withTtl(ttl);
            PeerConnection connection = network.links().ready(target);
            if (connection != null) {
                connection.send(forwarded);
                continue;
            }
            if (network.canQueueStatusBridge(peer)) {
                enqueueForwarded(target, forwarded);
            }
        }
    }

    void sendRelayedDirectoriesTo(String targetServer) {
        PeerConnection connection = network.links().ready(targetServer);
        NetworkConfig.PeerEntry targetPeer = network.directory().find(targetServer);
        if (connection == null && !network.canQueueStatusBridge(targetPeer)) {
            return;
        }
        for (Map.Entry<String, RelayedPortalAnnouncements> entry : relayedPortalAnnouncements.entrySet()) {
            String sourceServer = entry.getKey();
            if (sourceServer.equals(targetServer) || sourceServer.equals(network.getLocalName())) {
                continue;
            }
            for (WireMessage.Routed announcement : entry.getValue().snapshot()) {
                WireMessage.Routed forwarded = announcement.withTarget(targetServer).withTtl(ROUTE_TTL);
                if (connection != null) {
                    connection.send(forwarded);
                } else {
                    enqueueForwarded(targetServer, forwarded);
                }
            }
        }
    }

    private boolean forwardRouted(String inboundPeer, WireMessage.Routed forwarded) {
        String targetServer = forwarded.targetServer();
        NetworkConfig.PeerEntry directPeer = null;
        if (!targetServer.equals(inboundPeer)) {
            PeerConnection direct = network.links().ready(targetServer);
            if (direct != null) {
                return direct.send(forwarded);
            }
            directPeer = network.directory().find(targetServer);
            if (network.isStatusPeerReady(targetServer) && network.canQueueStatusBridge(directPeer) && enqueueForwarded(targetServer, forwarded)) {
                return true;
            }
        }
        String nextHop = routes.get(targetServer);
        if (nextHop != null && !nextHop.equals(inboundPeer)) {
            PeerConnection route = network.links().ready(nextHop);
            if (route != null && route.send(forwarded)) {
                return true;
            }
            NetworkConfig.PeerEntry routedPeer = network.directory().find(nextHop);
            if (network.canQueueStatusBridge(routedPeer) && enqueueForwarded(nextHop, forwarded)) {
                return true;
            }
        }
        return directPeer != null && network.canQueueStatusBridge(directPeer) && enqueueForwarded(targetServer, forwarded);
    }

    private boolean enqueueForwarded(String peerName, WireMessage.Routed message) {
        if (!network.sideband().enqueue(peerName, new OutboundFrame(message))) {
            return false;
        }
        if (SidebandQueue.isLatencyCritical(message)) {
            network.nudgeStatusPoll(peerName);
        }
        return true;
    }

    private void learnRoute(String sourceServer, String inboundPeer) {
        if (inboundPeer == null || inboundPeer.isBlank() || sourceServer.equals(inboundPeer) || sourceServer.equals(network.getLocalName())) {
            return;
        }
        if (network.links().containsReady(sourceServer)) {
            return;
        }
        routes.put(sourceServer, inboundPeer);
    }

    private static final class RelayedPortalAnnouncements {
        private final Map<UUID, WireMessage.Routed> changes = new LinkedHashMap<>();
        private WireMessage.Routed directory;

        synchronized void record(WireMessage.Routed routed, WireMessage message) {
            if (message instanceof WireMessage.PortalDirectory) {
                directory = routed;
                changes.clear();
                return;
            }
            if (message instanceof WireMessage.PortalUpsert upsert) {
                changes.put(upsert.portal().id(), routed);
                return;
            }
            if (message instanceof WireMessage.PortalRemove remove) {
                changes.put(remove.portalId(), routed);
            }
        }

        synchronized List<WireMessage.Routed> snapshot() {
            List<WireMessage.Routed> snapshot = new ArrayList<>(changes.size() + (directory == null ? 0 : 1));
            if (directory != null) {
                snapshot.add(directory);
            }
            snapshot.addAll(changes.values());
            return snapshot;
        }
    }
}
