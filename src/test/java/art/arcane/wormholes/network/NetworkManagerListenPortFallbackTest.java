package art.arcane.wormholes.network;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import art.arcane.wormholes.util.BukkitJsonDocuments;
import art.arcane.wormholes.config.toml.NetworkConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(20)
class NetworkManagerListenPortFallbackTest {
    private static final Logger LOGGER = Logger.getLogger("NetworkManagerListenPortFallbackTest");

    @TempDir
    Path tempDir;

    private NetworkManager manager;
    private ServerSocket blocker;

    @AfterEach
    void tearDown() throws IOException {
        if (manager != null) {
            manager.stop();
        }
        if (blocker != null && !blocker.isClosed()) {
            blocker.close();
        }
    }

    @Test
    void fallbackWindowStopsAtMaximumTcpPort() {
        assertEquals(65_535, PeerListener.fallbackUpperPort(65_530));
        assertEquals(65_535, PeerListener.fallbackUpperPort(65_535));
    }

    @Test
    void listenPortAutoFallsBackOverRange() throws IOException {
        blocker = reserveContiguousPorts(1)[0];
        int basePort = blocker.getLocalPort();

        NetworkConfig config = new NetworkConfig();
        config.enabled = true;
        config.serverName = "fallback-host";
        config.listenPort = basePort;
        config.advertiseHostOverride = "127.0.0.1";

        manager = new NetworkManager(LOGGER, new NetworkManager.Options( config, "26.2", "test", 25565, tempDir, BukkitJsonDocuments.INSTANCE, ClientVersion.getLatest().getProtocolVersion()));
        manager.start();

        assertTrue(manager.isRunning());
        int bound = manager.getBoundListenPort();
        assertNotEquals(basePort, bound, "expected fallback to a free port above " + basePort);
        assertTrue(bound > basePort && bound <= basePort + 50, "bound port " + bound + " should be inside the fallback window");
    }

    private static ServerSocket[] reserveContiguousPorts(int count) throws IOException {
        for (int basePort = 20_000; basePort + count - 1 <= 65_000; basePort += 53) {
            ServerSocket[] hold = new ServerSocket[count];
            boolean reserved = true;
            for (int i = 0; i < count; i++) {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(false);
                try {
                    s.bind(new InetSocketAddress("0.0.0.0", basePort + i));
                } catch (IOException ex) {
                    s.close();
                    reserved = false;
                    break;
                }
                hold[i] = s;
            }
            if (reserved) {
                return hold;
            }
            for (ServerSocket s : hold) {
                if (s != null && !s.isClosed()) {
                    s.close();
                }
            }
        }
        throw new IOException("could not reserve " + count + " contiguous free ports under 65535");
    }

    @Test
    void sidebandOnlyWhenEntireFallbackRangeIsBusy() throws IOException {
        ServerSocket[] hold = reserveContiguousPorts(51);
        int basePort = hold[0].getLocalPort();
        try {
            NetworkConfig config = new NetworkConfig();
            config.enabled = true;
            config.serverName = "no-bind";
            config.listenPort = basePort;
            config.advertiseHostOverride = "127.0.0.1";

            manager = new NetworkManager(LOGGER, new NetworkManager.Options( config, "26.2", "test", 25565, tempDir, BukkitJsonDocuments.INSTANCE, ClientVersion.getLatest().getProtocolVersion()));
            manager.start();
            assertTrue(manager.isRunning());
            assertEquals(basePort, manager.getBoundListenPort(), "sideband-only mode should fall back to configured listen-port for getBoundListenPort() reporting");
        } finally {
            for (ServerSocket s : hold) {
                if (s != null && !s.isClosed()) {
                    s.close();
                }
            }
        }
    }
}
