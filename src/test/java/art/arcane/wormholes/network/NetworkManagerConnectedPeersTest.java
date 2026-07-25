package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

@Timeout(60)
class NetworkManagerConnectedPeersTest {
    private static final Logger LOGGER = Logger.getLogger("NetworkManagerConnectedPeersTest");
    private static final String ALPHA_NAME = "alpha";
    private static final String BETA_NAME = "beta";

    @TempDir
    Path tempDir;

    private final List<NetworkManager> managers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (NetworkManager manager : managers) {
            manager.stop();
        }
        managers.clear();
    }

    @Test
    void connectedPeerGaugeTracksReadyLinksWithoutBuildingPeerSnapshots() throws IOException {
        int portAlpha = freePort();
        int portBeta = freePort();
        NetworkManager alpha = manager(config(portAlpha, ALPHA_NAME), 25565, "alpha");
        NetworkManager beta = manager(config(portBeta, BETA_NAME), 25566, "beta");

        assertEquals(0, alpha.connectedPeers());
        assertEquals(0, alpha.knownPeerCount());

        alpha.savePeer(route(BETA_NAME, portBeta));

        assertEquals(1, alpha.knownPeerCount());
        assertEquals(0, alpha.connectedPeers());

        alpha.start();
        beta.start();

        awaitTrue("alpha counts beta as a connected peer", () -> alpha.connectedPeers() == 1, 20_000L);
        awaitTrue("beta counts alpha as a connected peer", () -> beta.connectedPeers() == 1, 20_000L);

        beta.stop();

        awaitTrue("alpha drops the gauge when the link closes", () -> alpha.connectedPeers() == 0, 20_000L);
        assertEquals(0, beta.connectedPeers());
        assertEquals(1, alpha.knownPeerCount());
    }

    private NetworkManager manager(NetworkConfig config, int gamePort, String identityName) {
        NetworkManager manager = new NetworkManager(LOGGER, config, "26.2", "test", gamePort, tempDir.resolve(identityName));
        managers.add(manager);
        return manager;
    }

    private static NetworkConfig config(int listenPort, String serverName) {
        NetworkConfig config = new NetworkConfig();
        config.enabled = true;
        config.serverName = serverName;
        config.advertiseHostOverride = "127.0.0.1";
        config.listenPort = listenPort;
        return config;
    }

    private static NetworkConfig.PeerEntry route(String peerName, int peerPort) {
        NetworkConfig.PeerEntry peer = new NetworkConfig.PeerEntry();
        peer.name = peerName;
        peer.host = "127.0.0.1";
        peer.port = peerPort;
        return peer;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void awaitTrue(String what, BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for: " + what);
            }
        }
        fail("Timed out waiting for: " + what);
    }
}
