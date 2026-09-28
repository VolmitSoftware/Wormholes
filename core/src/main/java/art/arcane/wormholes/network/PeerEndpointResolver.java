package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

final class PeerEndpointResolver {
    private static final List<ClientEndpointRoutes.Cidr> LOCAL_NETWORKS = localNetworks();

    private PeerEndpointResolver() {
    }

    static GameEndpoint playerTransferEndpoint(NetworkConfig.PeerEntry peer, InetSocketAddress clientAddress,
                                                GameEndpoint verifiedPrivateEndpoint) {
        if (privateEndpointApplies(clientAddress, verifiedPrivateEndpoint, LOCAL_NETWORKS)) {
            return verifiedPrivateEndpoint;
        }
        GameEndpoint configured = GameEndpoint.optional(peer.publicHost, peer.publicPort);
        if (configured == null) {
            return null;
        }
        InetAddress literal = GameEndpoint.literal(configured.host());
        if (isLocalAddress(literal) && !privateEndpointApplies(clientAddress, configured, LOCAL_NETWORKS)) {
            return null;
        }
        return configured;
    }

    static GameEndpoint privateGameEndpoint(NetworkConfig.PeerEntry peer, GameEndpoint statusEndpoint,
                                            InetSocketAddress rawPeerAddress, boolean loopbackTransport) {
        if (statusEndpoint != null && isLocalAddress(GameEndpoint.literal(statusEndpoint.host()))) {
            return statusEndpoint;
        }
        int privatePort = peer.privatePort;
        if (privatePort <= 0) {
            return null;
        }
        if (loopbackTransport) {
            GameEndpoint advertised = GameEndpoint.optional(peer.privateHost, privatePort);
            if (advertised != null && isLocalAddress(GameEndpoint.literal(advertised.host()))) {
                return advertised;
            }
            return new GameEndpoint("127.0.0.1", privatePort);
        }
        if (isLocalClient(rawPeerAddress)) {
            return new GameEndpoint(rawPeerAddress.getAddress().getHostAddress(), privatePort);
        }
        return null;
    }

    static List<GameEndpoint> gameEndpoints(NetworkConfig.PeerEntry peer) {
        List<GameEndpoint> endpoints = new ArrayList<>(3);
        add(endpoints, GameEndpoint.optional(peer.publicHost, peer.publicPort));
        add(endpoints, GameEndpoint.optional(peer.privateHost, peer.privatePort));
        return List.copyOf(endpoints);
    }

    static boolean isLocalClient(InetSocketAddress clientAddress) {
        return clientAddress != null && isLocalAddress(clientAddress.getAddress());
    }

    static boolean isLocalAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress()) {
            return false;
        }
        if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    static boolean privateEndpointApplies(InetSocketAddress clientAddress, GameEndpoint endpoint,
                                          List<ClientEndpointRoutes.Cidr> localNetworks) {
        InetAddress client = clientAddress == null ? null : clientAddress.getAddress();
        InetAddress destination = endpoint == null ? null : GameEndpoint.literal(endpoint.host());
        if (!isLocalAddress(client) || !isLocalAddress(destination)) {
            return false;
        }
        if (destination.isLoopbackAddress()) {
            return client.isLoopbackAddress();
        }
        if (destination.isLinkLocalAddress()) {
            return false;
        }
        if (client.isLoopbackAddress()) {
            return true;
        }
        for (ClientEndpointRoutes.Cidr network : localNetworks) {
            if (network.contains(client) && network.contains(destination)) {
                return true;
            }
        }
        return false;
    }

    private static List<ClientEndpointRoutes.Cidr> localNetworks() {
        List<ClientEndpointRoutes.Cidr> networks = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback() || network.isPointToPoint()) {
                    continue;
                }
                for (InterfaceAddress address : network.getInterfaceAddresses()) {
                    if (address.getNetworkPrefixLength() > 0 && isLocalAddress(address.getAddress())) {
                        networks.add(new ClientEndpointRoutes.Cidr(address.getAddress().getAddress(), address.getNetworkPrefixLength()));
                    }
                }
            }
        } catch (SocketException ignored) {
            return List.of();
        }
        return List.copyOf(networks);
    }

    private static void add(List<GameEndpoint> endpoints, GameEndpoint endpoint) {
        if (endpoint != null && !endpoints.contains(endpoint)) {
            endpoints.add(endpoint);
        }
    }
}
