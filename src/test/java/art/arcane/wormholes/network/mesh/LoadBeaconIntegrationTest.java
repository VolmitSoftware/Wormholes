package art.arcane.wormholes.network.mesh;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.NetworkManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Timeout(60)
class LoadBeaconIntegrationTest {
    private static final Logger LOGGER = Logger.getLogger("LoadBeaconIntegrationTest");

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

    private NetworkManager manager(String name, int listenPort, int gamePort) {
        NetworkConfig config = new NetworkConfig();
        config.enabled = true;
        config.serverName = name;
        config.advertiseHostOverride = "127.0.0.1";
        config.listenPort = listenPort;
        config.policy.beaconIntervalSec = 1;
        NetworkManager manager = new NetworkManager(LOGGER, config, "26.2", "test", gamePort, tempDir.resolve(name));
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
    void beaconsCarryTheLoadSourceAndDrainFlagToLinkedPeers() throws IOException {
        int portA = freePort();
        int portB = freePort();
        NetworkManager alpha = manager("alpha", portA, 25565);
        NetworkManager beta = manager("beta", portB, 25566);
        alpha.savePeer(route("beta", portB));
        beta.savePeer(route("alpha", portA));
        alpha.trustPeer("beta", beta.getPublicKey());
        beta.trustPeer("alpha", alpha.getPublicKey());
        alpha.beacons().setSource(new ServerLoadSource() {
            @Override
            public int online() {
                return 7;
            }

            @Override
            public int max() {
                return 40;
            }

            @Override
            public int reserved() {
                return 2;
            }

            @Override
            public double tps() {
                return 19.5D;
            }

            @Override
            public double msptP95() {
                return 33.0D;
            }
        });

        alpha.start();
        beta.start();
        awaitTrue("link", () -> alpha.isPeerReady("beta") && beta.isPeerReady("alpha"), 15_000L);
        awaitTrue("beta received alpha beacon", () -> beta.loads().latest("alpha") != null, 10_000L);
        LoadBeacon beacon = beta.loads().latest("alpha");
        assertEquals(7, beacon.online());
        assertEquals(40, beacon.max());
        assertEquals(2, beacon.reserved());
        assertEquals(31, beacon.headroom());
        assertEquals(19.5D, beacon.tps());
        assertEquals(33.0D, beacon.msptP95());
        assertFalse(beacon.drain());
        assertFalse(beta.loads().isStale("alpha", System.currentTimeMillis(), 20_000L));

        alpha.drain().set(true);
        awaitTrue("beta sees alpha draining", () -> beta.loads().latest("alpha") != null && beta.loads().latest("alpha").drain(), 10_000L);
        assertTrue(Files.isRegularFile(tempDir.resolve("alpha").resolve("mesh").resolve("drain.flag")));
        alpha.drain().set(false);
        awaitTrue("beta sees alpha back in rotation", () -> !beta.loads().latest("alpha").drain(), 10_000L);
        assertFalse(Files.exists(tempDir.resolve("alpha").resolve("mesh").resolve("drain.flag")));
    }

    @Test
    void drainFlagPersistsAcrossRestarts() throws IOException {
        DrainMode first = DrainMode.loadOrCreate(tempDir.resolve("alpha"));
        assertFalse(first.isDraining());
        first.set(true);
        assertTrue(DrainMode.loadOrCreate(tempDir.resolve("alpha")).isDraining());
        first.set(false);
        assertFalse(DrainMode.loadOrCreate(tempDir.resolve("alpha")).isDraining());
    }
}
