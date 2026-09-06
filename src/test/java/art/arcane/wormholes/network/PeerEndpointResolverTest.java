package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerEndpointResolverTest {
    @Test
    void lanPlayersNeverReceiveTheirOwnLoopbackAddress() {
        GameEndpoint loopback = new GameEndpoint("127.0.0.1", 25566);
        assertFalse(PeerEndpointResolver.privateEndpointApplies(client("192.168.1.10"), loopback, lan()));
        assertTrue(PeerEndpointResolver.privateEndpointApplies(client("127.0.0.1"), loopback, lan()));
    }

    @Test
    void privateRoutingRequiresActualSharedInterfaceSubnet() {
        GameEndpoint sameSubnet = new GameEndpoint("192.168.1.20", 25566);
        GameEndpoint anotherNetwork = new GameEndpoint("10.0.0.20", 25566);
        assertTrue(PeerEndpointResolver.privateEndpointApplies(client("192.168.1.10"), sameSubnet, lan()));
        assertFalse(PeerEndpointResolver.privateEndpointApplies(client("192.168.2.10"), sameSubnet, lan()));
        assertFalse(PeerEndpointResolver.privateEndpointApplies(client("192.168.1.10"), anotherNetwork, lan()));
        assertFalse(PeerEndpointResolver.privateEndpointApplies(client("192.168.1.10"), sameSubnet, List.of()));
    }

    @Test
    void privateAndPublicGamePortsRemainIndependent() {
        NetworkConfig.PeerEntry peer = peer();
        GameEndpoint privateEndpoint = PeerEndpointResolver.privateGameEndpoint(peer, null, null, true);
        assertEquals(new GameEndpoint("192.168.1.20", 25566), privateEndpoint);
        assertEquals(privateEndpoint, PeerEndpointResolver.playerTransferEndpoint(peer, client("127.0.0.1"), privateEndpoint));
        assertEquals(new GameEndpoint("play.example", 25580),
            PeerEndpointResolver.playerTransferEndpoint(peer, client("198.51.100.10"), privateEndpoint));
    }

    @Test
    void rawPortAndRawFallbackHostsAreNeverUsedAsGameEndpoints() {
        NetworkConfig.PeerEntry peer = peer();
        peer.host = "wire.example";
        peer.fallbackHosts = "10.20.30.40,127.0.0.1";
        peer.port = 8902;
        assertEquals(List.of(new GameEndpoint("play.example", 25580), new GameEndpoint("192.168.1.20", 25566)),
            PeerEndpointResolver.gameEndpoints(peer));
    }

    @Test
    void remotePlayersCannotUseAnAdvertisedPrivateOnlyAddress() {
        NetworkConfig.PeerEntry peer = peer();
        peer.publicHost = "192.168.1.20";
        assertNull(PeerEndpointResolver.playerTransferEndpoint(peer, client("198.51.100.10"), null));
        assertNull(PeerEndpointResolver.playerTransferEndpoint(peer, null, null));
    }

    @Test
    void signedStatusEndpointRetainsItsActualPort() {
        NetworkConfig.PeerEntry peer = peer();
        GameEndpoint observed = new GameEndpoint("10.0.0.8", 25577);
        assertEquals(observed, PeerEndpointResolver.privateGameEndpoint(peer, observed, null, false));
        assertNull(PeerEndpointResolver.privateGameEndpoint(peer, null, null, false));
    }

    @Test
    void cidrOverridesUseLongestPrefixAndMatchDestination() {
        NetworkConfig.ClientRoute broad = route("10.0.0.0/8", "vpn.example", 25570);
        NetworkConfig.ClientRoute narrow = route("10.2.0.0/16", "lan.example", 25571);
        ClientEndpointRoutes routes = new ClientEndpointRoutes(List.of(broad, narrow));
        assertEquals(new GameEndpoint("lan.example", 25571), routes.resolve("beta", client("10.2.3.4")));
        assertEquals(new GameEndpoint("vpn.example", 25570), routes.resolve("beta", client("10.3.3.4")));
        assertNull(routes.resolve("gamma", client("10.2.3.4")));
        assertNull(routes.resolve("beta", client("192.168.1.1")));
        assertNull(routes.resolve("beta", InetSocketAddress.createUnresolved("workstation.example", 12345)));
    }

    @Test
    void ipv6ClientRoutesDoNotMatchIpv4Clients() {
        ClientEndpointRoutes routes = new ClientEndpointRoutes(List.of(route("fd12:3456::/48", "[fd12:3456::8]", 25566)));
        assertEquals(new GameEndpoint("fd12:3456::8", 25566), routes.resolve("beta", client("fd12:3456::42")));
        assertNull(routes.resolve("beta", client("10.0.0.42")));
    }

    @Test
    void endpointsRejectHostPortAmbiguityAndWildcardAddresses() {
        assertEquals(new GameEndpoint("::1", 25566), new GameEndpoint("[::1]", 25566));
        assertThrows(IllegalArgumentException.class, () -> new GameEndpoint("play.example:25566", 25565));
        assertThrows(IllegalArgumentException.class, () -> new GameEndpoint("https://play.example", 25565));
        assertThrows(IllegalArgumentException.class, () -> new GameEndpoint("0.0.0.0", 25565));
        assertThrows(IllegalArgumentException.class, () -> new GameEndpoint("::", 25565));
        assertThrows(IllegalArgumentException.class, () -> new GameEndpoint("224.0.0.1", 25565));
        assertThrows(IllegalArgumentException.class, () -> new GameEndpoint("play.example", 65536));
        assertThrows(IllegalArgumentException.class, () -> new ClientEndpointRoutes(List.of(route("10.0.0.1/40", "host.example", 25565))));
    }

    private static List<ClientEndpointRoutes.Cidr> lan() {
        return List.of(ClientEndpointRoutes.Cidr.parse("192.168.1.0/24"));
    }

    private static InetSocketAddress client(String address) {
        return new InetSocketAddress(InetAddress.ofLiteral(address), 60123);
    }

    private static NetworkConfig.PeerEntry peer() {
        NetworkConfig.PeerEntry peer = new NetworkConfig.PeerEntry();
        peer.name = "beta";
        peer.publicHost = "play.example";
        peer.publicPort = 25580;
        peer.privateHost = "192.168.1.20";
        peer.privatePort = 25566;
        return peer;
    }

    private static NetworkConfig.ClientRoute route(String cidr, String host, int port) {
        NetworkConfig.ClientRoute route = new NetworkConfig.ClientRoute();
        route.server = "beta";
        route.clientCidr = cidr;
        route.host = host;
        route.port = port;
        return route;
    }
}
