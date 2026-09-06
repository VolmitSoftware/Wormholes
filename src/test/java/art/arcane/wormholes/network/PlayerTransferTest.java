package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerTransferTest {
    private ClientProtocolFixture protocolApi;

    @BeforeEach
    void installProtocolLookup() {
        protocolApi = new ClientProtocolFixture(ClientVersion.V_26_2);
    }

    @AfterEach
    void restoreProtocolLookup() {
        protocolApi.close();
    }

    @Test
    void loopbackClientUsesPublicEndpointWithoutPrivateRouteEvidence() throws Exception {
        NetworkConfig.PeerEntry peer = route();
        AtomicReference<TransferCall> call = new AtomicReference<>();
        Player player = player(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 60123), call, false);

        assertTrue(PlayerTransfer.send(player, peer, "direct"));
        assertEquals(new TransferCall("204.111.10.237", 25566), call.get());
    }

    @Test
    void localhostPlayerDoesNotReceiveSeparatelyHostedDockerAddress() throws Exception {
        NetworkConfig.PeerEntry peer = new NetworkConfig.PeerEntry();
        peer.name = "hosted";
        peer.host = "217.217.30.150";
        peer.fallbackHosts = "172.18.0.7";
        peer.publicHost = "217.217.30.150";
        peer.publicPort = 25565;
        InetSocketAddress client = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 49380);

        assertNull(PeerEndpointResolver.privateGameEndpoint(
            peer, new GameEndpoint("217.217.30.150", 25565), null, false));
        assertEquals(new GameEndpoint("217.217.30.150", 25565), PeerEndpointResolver.playerTransferEndpoint(peer, client, null));
    }

    @Test
    void loopbackClientUsesVerifiedPrivateEndpointWithDestinationGamePort() throws Exception {
        NetworkConfig.PeerEntry peer = route();
        AtomicReference<TransferCall> call = new AtomicReference<>();
        Player player = player(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 60123), call, false);

        assertTrue(PlayerTransfer.send(player, peer, PlayerTransfer.Method.DIRECT, new GameEndpoint("192.168.1.42", 25567)));
        assertEquals(new TransferCall("192.168.1.42", 25567), call.get());
    }

    @Test
    void publicClientUsesPublicEndpoint() throws Exception {
        NetworkConfig.PeerEntry peer = route();
        InetSocketAddress client = new InetSocketAddress(InetAddress.getByName("198.51.100.7"), 60123);

        assertEquals(new GameEndpoint("204.111.10.237", 25566), PeerEndpointResolver.playerTransferEndpoint(peer, client, null));
        assertEquals(new GameEndpoint("204.111.10.237", 25566), PeerEndpointResolver.playerTransferEndpoint(peer, client, new GameEndpoint("192.168.1.42", 25567)));
    }

    @Test
    void unknownClientUsesPublicEndpoint() {
        NetworkConfig.PeerEntry peer = route();

        assertEquals(new GameEndpoint("204.111.10.237", 25566), PeerEndpointResolver.playerTransferEndpoint(peer, null, null));
        assertEquals(new GameEndpoint("204.111.10.237", 25566), PeerEndpointResolver.playerTransferEndpoint(peer,
            InetSocketAddress.createUnresolved("workstation.lan", 60123), null));
    }

    @Test
    void localHostnameIsNotResolvedOnTransferThread() throws Exception {
        NetworkConfig.PeerEntry peer = route();
        peer.fallbackHosts = "destination.internal";
        InetSocketAddress client = new InetSocketAddress(InetAddress.getByName("10.0.0.12"), 60123);

        assertEquals(new GameEndpoint("204.111.10.237", 25566), PeerEndpointResolver.playerTransferEndpoint(peer, client, null));
    }

    @Test
    void ipv6LoopbackClientUsesVerifiedUniqueLocalEndpoint() throws Exception {
        NetworkConfig.PeerEntry peer = route();
        peer.fallbackHosts = "fd00::42";
        InetSocketAddress client = new InetSocketAddress(InetAddress.getByName("::1"), 60123);

        assertEquals(new GameEndpoint("fd00::42", 25567), PeerEndpointResolver.playerTransferEndpoint(peer, client, new GameEndpoint("fd00::42", 25567)));
    }

    @Test
    void privateRouteRequiresTopologyEvidence() throws Exception {
        NetworkConfig.PeerEntry peer = route();

        assertNull(PeerEndpointResolver.privateGameEndpoint(peer, null, null, false));
        assertEquals(new GameEndpoint("192.168.1.42", 25567), PeerEndpointResolver.privateGameEndpoint(
            peer, new GameEndpoint("192.168.1.42", 25567), null, false));
        assertEquals(new GameEndpoint("10.20.30.40", 25567), PeerEndpointResolver.privateGameEndpoint(
            peer,
            null,
            new InetSocketAddress(InetAddress.getByName("10.20.30.40"), 8901),
            false
        ));
    }

    @Test
    void rejectedPaperTransferReturnsFalse() throws Exception {
        NetworkConfig.PeerEntry peer = route();
        Player player = player(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 60123),
            new AtomicReference<>(), true);

        assertFalse(PlayerTransfer.send(player, peer, "direct"));
    }

    @Test
    void automaticModeHonorsProxyFlag() {
        NetworkConfig.PeerEntry peer = route();
        peer.useProxy = true;

        assertTrue(PlayerTransfer.usesProxy(peer, "auto"));
        assertFalse(PlayerTransfer.usesProxy(peer, "direct"));
        assertTrue(PlayerTransfer.hasDirectHost(peer));
    }

    @Test
    void oldOrUnidentifiedClientsStayOnTheSourceServer() {
        AtomicReference<TransferCall> call = new AtomicReference<TransferCall>();
        Player player = player(new InetSocketAddress("127.0.0.1", 60123), call, false);
        for (ClientVersion version : new ClientVersion[] {ClientVersion.V_1_20_3, ClientVersion.UNKNOWN, null}) {
            protocolApi.version(version);
            assertFalse(PlayerTransfer.send(player, route(), "direct"));
            assertNull(call.get());
        }
        assertTrue(PlayerTransfer.supportsClientTransfer(ClientVersion.V_1_20_5));
        assertTrue(PlayerTransfer.supportsClientTransfer(ClientVersion.V_26_2));
    }

    @Test
    void missingProtocolLookupDoesNotDispatchATransfer() {
        PacketEvents.setAPI(null);
        AtomicReference<TransferCall> call = new AtomicReference<TransferCall>();
        Player player = player(new InetSocketAddress("127.0.0.1", 60123), call, false);

        assertFalse(PlayerTransfer.send(player, route(), "direct"));
        assertNull(call.get());
    }

    @Test
    void unavailableClientRouteDoesNotDispatchATransfer() {
        AtomicReference<TransferCall> call = new AtomicReference<TransferCall>();
        Player player = player(new InetSocketAddress("127.0.0.1", 60123), call, false);

        assertFalse(PlayerTransfer.send(player, route(), PlayerTransfer.Method.DIRECT, null));
        assertNull(call.get());
    }

    private static NetworkConfig.PeerEntry route() {
        NetworkConfig.PeerEntry peer = new NetworkConfig.PeerEntry();
        peer.name = "beta";
        peer.host = "204.111.10.237";
        peer.fallbackHosts = "192.168.1.42,127.0.0.1";
        peer.publicHost = "204.111.10.237";
        peer.publicPort = 25566;
        peer.privateHost = "192.168.1.42";
        peer.privatePort = 25567;
        return peer;
    }

    private static Player player(InetSocketAddress address, AtomicReference<TransferCall> call, boolean reject) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getAddress" -> address;
                case "getName" -> "Traveler";
                case "transfer" -> {
                    call.set(new TransferCall((String) arguments[0], ((Integer) arguments[1]).intValue()));
                    if (reject) {
                        throw new IllegalArgumentException("transfer unavailable");
                    }
                    yield null;
                }
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                case "toString" -> "PlayerTransferTestPlayer";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private record TransferCall(String host, int port) {
    }
}
