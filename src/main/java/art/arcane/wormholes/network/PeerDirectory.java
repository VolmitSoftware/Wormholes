package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

final class PeerDirectory {
    private final NetworkManager network;
    private final Logger logger;
    private final PeerRouteStore routeStore;
    private final Map<String, NetworkConfig.PeerEntry> learnedPeers = new ConcurrentHashMap<>();

    PeerDirectory(NetworkManager network, Logger logger, PeerRouteStore routeStore) {
        this.network = network;
        this.logger = logger;
        this.routeStore = routeStore;
        for (NetworkConfig.PeerEntry route : routeStore.all()) {
            learnedPeers.put(route.name, route);
        }
    }

    NetworkConfig.PeerEntry find(String name) {
        return learnedPeers.get(name);
    }

    Collection<NetworkConfig.PeerEntry> all() {
        return learnedPeers.values();
    }

    List<NetworkConfig.PeerEntry> known() {
        List<NetworkConfig.PeerEntry> peers = new ArrayList<>(learnedPeers.size());
        for (NetworkConfig.PeerEntry peer : learnedPeers.values()) {
            if (peer.name != null && !peer.name.isBlank()) {
                peers.add(peer);
            }
        }
        return peers;
    }

    void store(NetworkConfig.PeerEntry peer) {
        routeStore.save(peer);
        NetworkConfig.PeerEntry stored = routeStore.get(peer.name);
        if (stored != null) {
            learnedPeers.put(stored.name, stored);
        }
    }

    boolean remove(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        boolean forgotten = learnedPeers.remove(name) != null;
        return routeStore.remove(name) || forgotten;
    }

    void learnFromConnection(PeerConnection connection) {
        String name = connection.getPeerName();
        if (name == null || name.isBlank()) {
            return;
        }
        NetworkConfig.PeerEntry known = find(name);
        if (known == null) {
            if (connection.isDialer()) {
                return;
            }
            String host = connection.getPeerAdvertiseHost();
            if (host == null || connection.getPeerWormholePort() <= 0) {
                return;
            }
            NetworkConfig.PeerEntry learned = new NetworkConfig.PeerEntry();
            learned.name = name;
            learned.host = host;
            learned.port = connection.getPeerWormholePort();
            learned.publicHost = host;
            learned.publicPort = connection.getPeerGamePort() > 0 ? connection.getPeerGamePort() : 25565;
            network.savePeer(learned);
            return;
        }
        if (autoPopulate(known, connection)) {
            network.savePeer(known);
        }
    }

    void learnFromStatusPacket(MinecraftStatusBridge.StatusPacket packet) {
        String sourceServer = packet.sourceServer();
        String replyHost = packet.replyHost();
        if (replyHost == null || replyHost.isBlank()) {
            return;
        }
        int replyPort = packet.replyPort() > 0 ? packet.replyPort() : 25565;
        NetworkConfig.PeerEntry known = find(sourceServer);
        if (known != null) {
            boolean changed = !replyHost.equals(known.publicHost) || replyPort != known.publicPort;
            if (changed && shouldAdoptAdvertisedHost(known.publicHost, replyHost)) {
                String previous = known.publicHost + ":" + known.publicPort;
                known.publicHost = replyHost;
                known.publicPort = replyPort;
                if (known.host == null || known.host.isBlank() || !isRoutableHost(known.host)) {
                    known.host = replyHost;
                }
                network.savePeer(known);
                logger.info("net: peer " + sourceServer + " advertised game-port address " + replyHost + ":" + replyPort + " (was " + previous + "); updated from signed status handshake");
            }
            return;
        }
        NetworkConfig.PeerEntry learned = new NetworkConfig.PeerEntry();
        learned.name = sourceServer;
        learned.host = replyHost;
        learned.port = network.activeConfig().listenPort;
        learned.publicHost = replyHost;
        learned.publicPort = replyPort;
        network.savePeer(learned);
    }

    private static boolean autoPopulate(NetworkConfig.PeerEntry peer, PeerConnection connection) {
        boolean changed = false;
        String advertised = connection.getPeerAdvertiseHost();
        int peerWirePort = connection.getPeerWormholePort();
        int peerGamePort = connection.getPeerGamePort();
        if ((peer.publicHost == null || peer.publicHost.isBlank()) && advertised != null && !advertised.isBlank()) {
            peer.publicHost = advertised;
            changed = true;
        }
        if (peerGamePort > 0 && peerGamePort != peer.publicPort) {
            peer.publicPort = peerGamePort;
            changed = true;
        }
        if ((peer.host == null || peer.host.isBlank()) && advertised != null && !advertised.isBlank()) {
            peer.host = advertised;
            changed = true;
        }
        if (peer.port <= 0 && peerWirePort > 0) {
            peer.port = peerWirePort;
            changed = true;
        }
        return changed;
    }

    private static boolean shouldAdoptAdvertisedHost(String current, String advertised) {
        if (current == null || current.isBlank()) {
            return true;
        }
        boolean advertisedRoutable = isRoutableHost(advertised);
        boolean currentRoutable = isRoutableHost(current);
        if (advertisedRoutable && !currentRoutable) {
            return true;
        }
        if (!advertisedRoutable && currentRoutable) {
            return false;
        }
        return true;
    }

    private static boolean isRoutableHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            return !address.isLoopbackAddress() && !address.isAnyLocalAddress()
                && !address.isLinkLocalAddress() && !address.isSiteLocalAddress();
        } catch (UnknownHostException e) {
            return true;
        }
    }

    static boolean isDialable(NetworkConfig.PeerEntry peer) {
        if (peer == null) {
            return false;
        }
        if (peer.host != null && !peer.host.isBlank()) {
            return true;
        }
        return peer.fallbackHosts != null && !peer.fallbackHosts.isBlank();
    }

    static boolean canUseStatusBridge(NetworkConfig.PeerEntry peer) {
        if (peer == null) {
            return false;
        }
        String host = peer.publicHost == null || peer.publicHost.isBlank() ? peer.host : peer.publicHost;
        return host != null && !host.isBlank();
    }

    static String peerAddress(NetworkConfig.PeerEntry peer) {
        if (peer.host == null || peer.host.isBlank()) {
            return "route unavailable";
        }
        return peer.host + ":" + peer.port;
    }

    static String statusBridgeAddress(NetworkConfig.PeerEntry peer) {
        String host = peer.publicHost == null || peer.publicHost.isBlank() ? peer.host : peer.publicHost;
        if (host == null || host.isBlank()) {
            return "game-port route unavailable";
        }
        int port = peer.publicPort > 0 ? peer.publicPort : 25565;
        return "game-port " + host + ":" + port;
    }
}
