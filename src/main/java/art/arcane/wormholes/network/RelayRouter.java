package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

final class RelayRouter {
    static final int ROUTE_TTL = 8;

    private final NetworkManager network;
    private final Logger logger;
    private final Map<String, String> routes = new ConcurrentHashMap<>();
    private final Map<String, List<PortalInfo>> relayedPortalDirectories = new ConcurrentHashMap<>();

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
            return true;
        }
        if (routed.sourceServer().equals(network.getLocalName())) {
            return true;
        }
        learnRoute(routed.sourceServer(), inboundPeer);
        if (routed.targetServer().equals(network.getLocalName())) {
            try {
                WireMessage inner = WireCodec.decodePayload(routed.innerType(), routed.payload());
                cacheAnnouncement(routed.sourceServer(), inner);
                relayAnnouncement(routed.sourceServer(), inboundPeer, routed.ttl() - 1, inner);
                network.deliverMessage(routed.sourceServer(), inner);
            } catch (IOException e) {
                logger.warning("net: dropped routed message from " + routed.sourceServer() + ": " + e.getMessage());
            }
            return true;
        }
        if (routed.ttl() <= 0) {
            return true;
        }
        return forwardRouted(inboundPeer, routed.sourceServer(), routed.targetServer(), routed.ttl() - 1, routed.innerType(), routed.payload());
    }

    boolean sendRouted(PeerConnection connection, String sourceServer, String targetServer, int ttl, WireMessage message) {
        if (message instanceof WireMessage.Routed) {
            return false;
        }
        try {
            return connection.send(new WireMessage.Routed(sourceServer, targetServer, ttl, message.type(), WireCodec.encodePayload(message)));
        } catch (IOException e) {
            logger.warning("net: could not route " + message.type() + " from " + sourceServer + " to " + targetServer + ": " + e.getMessage());
            return false;
        }
    }

    boolean enqueueRouted(String nextHop, String sourceServer, String targetServer, int ttl, WireMessage message) {
        if (message instanceof WireMessage.Routed) {
            return false;
        }
        try {
            WireMessage.Routed routed = new WireMessage.Routed(sourceServer, targetServer, ttl, message.type(), WireCodec.encodePayload(message));
            return enqueueForwarded(nextHop, routed);
        } catch (IOException e) {
            logger.warning("net: could not queue routed status sideband " + message.type() + " from " + sourceServer + " to " + targetServer + ": " + e.getMessage());
            return false;
        }
    }

    void cacheAnnouncement(String sourceServer, WireMessage message) {
        if (sourceServer == null || sourceServer.isBlank()) {
            return;
        }
        if (message instanceof WireMessage.PortalDirectory directory) {
            relayedPortalDirectories.put(sourceServer, List.copyOf(directory.portals()));
            return;
        }
        if (message instanceof WireMessage.PortalUpsert upsert) {
            relayedPortalDirectories.compute(sourceServer, (name, previous) -> {
                List<PortalInfo> next = previous == null ? new ArrayList<>() : new ArrayList<>(previous);
                next.removeIf(portal -> portal.id().equals(upsert.portal().id()));
                next.add(upsert.portal());
                return List.copyOf(next);
            });
            return;
        }
        if (message instanceof WireMessage.PortalRemove remove) {
            relayedPortalDirectories.computeIfPresent(sourceServer, (name, previous) -> {
                List<PortalInfo> next = new ArrayList<>(previous);
                next.removeIf(portal -> portal.id().equals(remove.portalId()));
                return List.copyOf(next);
            });
        }
    }

    void relayAnnouncement(String sourceServer, String inboundPeer, int ttl, WireMessage message) {
        if (ttl <= 0 || !isRelayAnnouncement(message)) {
            return;
        }
        for (NetworkConfig.PeerEntry peer : network.directory().known()) {
            String target = peer.name;
            if (target.equals(inboundPeer) || target.equals(sourceServer) || target.equals(network.getLocalName())) {
                continue;
            }
            PeerConnection connection = network.links().ready(target);
            if (connection != null) {
                sendRouted(connection, sourceServer, target, ttl, message);
                continue;
            }
            if (network.canQueueStatusBridge(peer)) {
                enqueueRouted(target, sourceServer, target, ttl, message);
            }
        }
    }

    void sendRelayedDirectoriesTo(String targetServer) {
        PeerConnection connection = network.links().ready(targetServer);
        NetworkConfig.PeerEntry targetPeer = network.directory().find(targetServer);
        if (connection == null && !network.canQueueStatusBridge(targetPeer)) {
            return;
        }
        for (Map.Entry<String, List<PortalInfo>> entry : relayedPortalDirectories.entrySet()) {
            String sourceServer = entry.getKey();
            if (sourceServer.equals(targetServer) || sourceServer.equals(network.getLocalName())) {
                continue;
            }
            WireMessage directory = new WireMessage.PortalDirectory(entry.getValue());
            if (connection != null) {
                sendRouted(connection, sourceServer, targetServer, ROUTE_TTL, directory);
            } else {
                enqueueRouted(targetServer, sourceServer, targetServer, ROUTE_TTL, directory);
            }
        }
    }

    private boolean forwardRouted(String inboundPeer, String sourceServer, String targetServer, int ttl, WireMessageType innerType, byte[] payload) {
        WireMessage.Routed forwarded = new WireMessage.Routed(sourceServer, targetServer, ttl, innerType, payload);
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

    private static boolean isRelayAnnouncement(WireMessage message) {
        return message instanceof WireMessage.PortalDirectory
            || message instanceof WireMessage.PortalUpsert
            || message instanceof WireMessage.PortalRemove;
    }
}
