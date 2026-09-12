package art.arcane.wormholes.network.mesh;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.WireCapability;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Two builds with different Wormholes versions on real sockets: compatible policy links them, exact policy refuses. */
@Timeout(60)
class PluginVersionLinkTest {
    private static final Logger LOGGER = Logger.getLogger("PluginVersionLinkTest");

    @TempDir
    Path tempDir;

    private final List<NetworkManager> managers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (NetworkManager manager : managers) {
            manager.stop();
        }
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
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for: " + what);
            }
        }
        fail("Timed out waiting for: " + what);
    }

    private static void settle(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private NetworkManager manager(String name, int listenPort, int gamePort, String pluginVersion, String policy) {
        NetworkConfig config = new NetworkConfig();
        config.enabled = true;
        config.serverName = name;
        config.advertiseHostOverride = "127.0.0.1";
        config.listenPort = listenPort;
        config.pluginVersionPolicy = policy;
        NetworkManager manager = new NetworkManager(LOGGER, config, "26.2", pluginVersion, gamePort, tempDir.resolve(name));
        managers.add(manager);
        return manager;
    }

    private static NetworkConfig.PeerEntry route(String peerName, int peerPort) {
        NetworkConfig.PeerEntry peer = new NetworkConfig.PeerEntry();
        peer.name = peerName;
        peer.host = "127.0.0.1";
        peer.port = peerPort;
        return peer;
    }

    @Test
    void compatiblePolicyLinksMixedPluginVersionsAndReportsThem() throws IOException {
        int portA = freePort();
        int portB = freePort();
        NetworkManager alpha = manager("alpha", portA, 25565, "2.1.0", NetworkConfig.PLUGIN_VERSION_POLICY_COMPATIBLE);
        NetworkManager beta = manager("beta", portB, 25566, "2.2.0", NetworkConfig.PLUGIN_VERSION_POLICY_COMPATIBLE);
        alpha.savePeer(route("beta", portB));
        beta.savePeer(route("alpha", portA));
        alpha.trustPeer("beta", beta.getPublicKey());
        beta.trustPeer("alpha", alpha.getPublicKey());

        alpha.start();
        beta.start();
        awaitTrue("mixed-version link", () -> alpha.isPeerReady("beta") && beta.isPeerReady("alpha"), 15_000L);
        awaitTrue("beta announce recorded at alpha", () -> alpha.members().get("beta") != null, 15_000L);

        VersionReport.Row row = VersionReport.build(alpha).stream().filter(entry -> entry.server().equals("beta")).findFirst().orElseThrow();
        assertEquals("2.2.0", row.pluginVersion());
        assertEquals(art.arcane.wormholes.network.WireCodec.PROTOCOL_VERSION, row.protocolVersion());
        assertEquals(WireCapability.localSet(), row.capabilities());
        assertFalse(row.reduced());
        assertTrue(row.ready());
    }

    @Test
    void exactPolicyRefusesADifferentPluginVersion() throws IOException {
        int portA = freePort();
        int portB = freePort();
        NetworkManager alpha = manager("alpha", portA, 25565, "2.1.0", NetworkConfig.PLUGIN_VERSION_POLICY_EXACT);
        NetworkManager beta = manager("beta", portB, 25566, "2.2.0", NetworkConfig.PLUGIN_VERSION_POLICY_EXACT);
        alpha.savePeer(route("beta", portB));
        beta.savePeer(route("alpha", portA));
        alpha.trustPeer("beta", beta.getPublicKey());
        beta.trustPeer("alpha", alpha.getPublicKey());

        alpha.start();
        beta.start();
        settle(3_000L);
        assertFalse(alpha.isPeerReady("beta"));
        assertFalse(beta.isPeerReady("alpha"));
    }
}
