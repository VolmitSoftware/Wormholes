package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ServerConnectServiceTest {
    private static final Logger LOGGER = Logger.getLogger("ServerConnectServiceTest");

    @TempDir
    Path tempDir;

    private NetworkManager network;

    @AfterEach
    void tearDown() {
        if (network != null) {
            network.stop();
            network = null;
        }
    }

    @Test
    void resolveNameMatchesCaseInsensitively() {
        network = manager("alpha");
        network.savePeer(route("Beta-Survival"));

        assertEquals("Beta-Survival", ServerConnectService.resolveName(network, "Beta-Survival"));
        assertEquals("Beta-Survival", ServerConnectService.resolveName(network, "beta-survival"));
        assertNull(ServerConnectService.resolveName(network, "gamma"));
        assertNull(ServerConnectService.resolveName(network, ""));
        assertNull(ServerConnectService.resolveName(null, "beta"));
    }

    @Test
    void unknownServerIsReported() throws Exception {
        network = manager("alpha");
        Player player = player(new AtomicReference<>(), new AtomicReference<>());

        assertEquals(ServerConnectService.Result.UNKNOWN_SERVER,
            ServerConnectService.connect(network, player, "beta", "auto"));
    }

    @Test
    void directTransferRequiresReadyPeer() throws Exception {
        network = manager("alpha");
        network.savePeer(route("beta"));
        Player player = player(new AtomicReference<>(), new AtomicReference<>());

        assertEquals(ServerConnectService.Result.NOT_READY,
            ServerConnectService.connect(network, player, "beta", "auto"));
    }

    @Test
    void readySidebandPeerTransfersDirectly() throws Exception {
        network = manager("alpha");
        network.savePeer(route("beta"));
        network.presence().mark("beta", System.currentTimeMillis(), 5L);
        AtomicReference<TransferCall> transfer = new AtomicReference<>();
        Player player = player(transfer, new AtomicReference<>());

        assertEquals(ServerConnectService.Result.SENT,
            ServerConnectService.connect(network, player, "beta", "auto"));
        assertEquals(new TransferCall("204.111.10.237", 25566), transfer.get());
    }

    @Test
    void proxyPeerSendsConnectPluginMessageWithoutReadiness() throws Exception {
        network = manager("alpha");
        NetworkConfig.PeerEntry peer = route("beta");
        peer.useProxy = true;
        network.savePeer(peer);
        AtomicReference<String> pluginMessageChannel = new AtomicReference<>();
        Player player = player(new AtomicReference<>(), pluginMessageChannel);

        assertEquals(ServerConnectService.Result.SENT,
            ServerConnectService.connect(network, player, "beta", "auto"));
        assertNotNull(pluginMessageChannel.get());
        assertEquals(PlayerTransfer.PROXY_CHANNEL, pluginMessageChannel.get());
    }

    private NetworkManager manager(String serverName) {
        NetworkConfig config = new NetworkConfig();
        config.enabled = true;
        config.serverName = serverName;
        config.advertiseHostOverride = "127.0.0.1";
        config.listenPort = 8901;
        return new NetworkManager(LOGGER, config, "26.2", "test", 25565, tempDir.resolve(serverName));
    }

    private static NetworkConfig.PeerEntry route(String name) {
        NetworkConfig.PeerEntry peer = new NetworkConfig.PeerEntry();
        peer.name = name;
        peer.host = "204.111.10.237";
        peer.publicHost = "204.111.10.237";
        peer.publicPort = 25566;
        return peer;
    }

    private static Player player(AtomicReference<TransferCall> transfer, AtomicReference<String> pluginMessageChannel) throws Exception {
        InetSocketAddress address = new InetSocketAddress(InetAddress.getByName("198.51.100.7"), 60123);
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getAddress" -> address;
                case "getName" -> "Traveler";
                case "transfer" -> {
                    transfer.set(new TransferCall((String) arguments[0], ((Integer) arguments[1]).intValue()));
                    yield null;
                }
                case "sendPluginMessage" -> {
                    pluginMessageChannel.set((String) arguments[1]);
                    yield null;
                }
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                case "toString" -> "ServerConnectTestPlayer";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private record TransferCall(String host, int port) {
    }
}
