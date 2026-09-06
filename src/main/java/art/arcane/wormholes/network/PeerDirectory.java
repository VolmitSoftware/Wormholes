package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        NetworkConfig.PeerEntry peer = learnedPeers.get(name);
        return peer == null ? null : PeerRouteStore.copy(peer);
    }

    Collection<NetworkConfig.PeerEntry> all() {
        return routeStore.all();
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
        GameEndpoint game = connection.getPeerGameEndpoint();
        if (name == null || name.isBlank() || game == null) {
            return;
        }
        NetworkConfig.PeerEntry known = find(name);
        if (known == null) {
            if (connection.isDialer()) {
                return;
            }
            known = new NetworkConfig.PeerEntry();
            known.name = name;
            known.host = connection.getPeerAdvertiseHost();
            known.port = connection.getPeerWormholePort();
        }
        GameEndpoint privateGame = connection.getPeerPrivateGameEndpoint();
        storeAdvertisedEndpoints(known, game, privateGame, connection.getPeerAdvertiseHost(), connection.getPeerWormholePort());
    }

    void learnFromStatusPacket(MinecraftStatusBridge.StatusPacket packet) {
        GameEndpoint game = GameEndpoint.optional(packet.replyHost(), packet.replyPort());
        if (game == null) {
            return;
        }
        NetworkConfig.PeerEntry known = find(packet.sourceServer());
        if (known == null) {
            known = new NetworkConfig.PeerEntry();
            known.name = packet.sourceServer();
            known.port = 0;
        }
        storeAdvertisedEndpoints(known, game, packet.privateGameEndpoint(), packet.peerHost(), packet.peerPort());
    }

    private void storeAdvertisedEndpoints(NetworkConfig.PeerEntry peer, GameEndpoint game, GameEndpoint privateGame,
                                          String wireHost, int wirePort) {
        GameEndpoint current = GameEndpoint.optional(peer.publicHost, peer.publicPort);
        GameEndpoint currentPrivate = GameEndpoint.optional(peer.privateHost, peer.privatePort);
        if (game.equals(current) && Objects.equals(privateGame, currentPrivate)
            && Objects.equals(peer.host, wireHost) && peer.port == wirePort && find(peer.name) != null) {
            return;
        }
        peer.host = wireHost;
        peer.port = wirePort;
        peer.publicHost = game.host();
        peer.publicPort = game.port();
        peer.privateHost = privateGame == null ? "" : privateGame.host();
        peer.privatePort = privateGame == null ? 0 : privateGame.port();
        network.savePeer(peer);
        if (current != null && !game.equals(current)) {
            logger.info("net: peer " + peer.name + " advertised game endpoint " + game.display()
                + " (was " + current.display() + ")");
        }
    }

    static boolean isDialable(NetworkConfig.PeerEntry peer) {
        return peer != null && peer.port > 0 && peer.port <= 65_535
            && ((peer.host != null && !peer.host.isBlank())
            || (peer.fallbackHosts != null && !peer.fallbackHosts.isBlank()));
    }

    static boolean canUseStatusBridge(NetworkConfig.PeerEntry peer) {
        return peer != null && !PeerEndpointResolver.gameEndpoints(peer).isEmpty();
    }

    static String peerAddress(NetworkConfig.PeerEntry peer) {
        if (peer.host == null || peer.host.isBlank()) {
            return "route unavailable";
        }
        return peer.host + ":" + peer.port;
    }

    static String statusBridgeAddress(NetworkConfig.PeerEntry peer) {
        List<GameEndpoint> endpoints = PeerEndpointResolver.gameEndpoints(peer);
        return endpoints.isEmpty() ? "game-port route unavailable" : "game-port " + endpoints.getFirst().display();
    }
}
