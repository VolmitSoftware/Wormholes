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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkConfigurationValidationTest {
    @TempDir
    Path tempDirectory;

    @Test
    void rejectedHostAndRouteUpdatesKeepTheActiveConfigAndClientRoute() {
        NetworkConfig initial = config();
        NetworkManager network = new NetworkManager(Logger.getLogger(getClass().getName()), initial,
            "26.2", "test", 25565, tempDirectory);
        try {
            NetworkConfig invalidHost = config();
            invalidHost.gameHostOverride = "bad host";
            NetworkConfig invalidPrivateHost = config();
            invalidPrivateHost.privateGameHostOverride = "0.0.0.0";
            NetworkConfig invalidRoute = config();
            invalidRoute.clientRoutes.getFirst().clientCidr = "10.0.0.0/99";
            for (NetworkConfig rejected : List.of(invalidHost, invalidPrivateHost, invalidRoute)) {
                assertThrows(IllegalArgumentException.class, () -> network.applyConfig(rejected));
                assertSame(initial, network.activeConfig());
                assertEquals(new GameEndpoint("play.example", 25565), network.gameEndpoint());
                assertEquals(new GameEndpoint("lan.example", 25566), network.playerEndpoint("beta",
                    new InetSocketAddress(InetAddress.ofLiteral("10.1.2.3"), 50000)));
            }
        } finally {
            network.stop();
        }
    }

    private static NetworkConfig config() {
        NetworkConfig config = new NetworkConfig();
        config.gameHostOverride = "play.example";
        NetworkConfig.ClientRoute route = new NetworkConfig.ClientRoute();
        route.server = "beta";
        route.clientCidr = "10.0.0.0/8";
        route.host = "lan.example";
        route.port = 25566;
        config.clientRoutes.add(route);
        return config;
    }
}
