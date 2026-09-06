package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class NetworkIdentityGameBindTest {
    @TempDir
    Path tempDirectory;

    @Test
    void loopbackBoundGameListenerAdvertisesTheListeningPrivateEndpoint() {
        NetworkManager network = network(new NetworkConfig());
        network.setGameBindHost("127.0.0.1");
        try {
            GameEndpoint privateEndpoint = network.localPrivateGameEndpoint();
            assertEquals(new GameEndpoint("127.0.0.1", 25566), privateEndpoint);
            assertEquals(new GameEndpoint("play.example", 25580), network.gameEndpoint());
            NetworkConfig.PeerEntry peer = new NetworkConfig.PeerEntry();
            peer.publicHost = "play.example";
            peer.publicPort = 25580;
            peer.privateHost = privateEndpoint.host();
            peer.privatePort = privateEndpoint.port();
            assertEquals(privateEndpoint, PeerEndpointResolver.playerTransferEndpoint(peer,
                new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), 50000), privateEndpoint));
        } finally {
            network.stop();
        }
    }

    @Test
    void explicitPrivateEndpointTakesPrecedenceOverTheGameBind() {
        NetworkConfig config = new NetworkConfig();
        config.privateGameHostOverride = "10.2.3.4";
        config.privateGamePortOverride = 25590;
        NetworkManager network = network(config);
        network.setGameBindHost("127.0.0.1");
        try {
            assertEquals(new GameEndpoint("10.2.3.4", 25590), network.localPrivateGameEndpoint());
        } finally {
            network.stop();
        }
    }

    @Test
    void wildcardBindsUseDetectedLocalAddressAndKeepTheActualGamePort() {
        NetworkManager network = network(new NetworkConfig());
        try {
            for (String host : List.of("", "0.0.0.0", "::")) {
                network.setGameBindHost(host);
                GameEndpoint endpoint = network.localPrivateGameEndpoint();
                InetAddress address = GameEndpoint.literal(endpoint.host());
                assertNotNull(address);
                assertFalse(address.isAnyLocalAddress());
                assertEquals(25566, endpoint.port());
            }
            network.setGameBindHost("::1");
            assertEquals(new GameEndpoint("::1", 25566), network.localPrivateGameEndpoint());
        } finally {
            network.stop();
        }
    }

    private NetworkManager network(NetworkConfig config) {
        config.serverName = "alpha";
        config.gameHostOverride = "play.example";
        config.gamePortOverride = 25580;
        return new NetworkManager(Logger.getLogger(getClass().getName()), config, "26.2", "test", 25566, tempDirectory);
    }
}
