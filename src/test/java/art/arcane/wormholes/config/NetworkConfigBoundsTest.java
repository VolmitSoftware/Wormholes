package art.arcane.wormholes.config;

import art.arcane.wormholes.config.toml.NetworkConfig;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkConfigBoundsTest {
    @Test
    void invalidNetworkBoundsNormalizeToOperationalValues() {
        NetworkConfig network = new NetworkConfig();
        network.listenPort = Integer.MAX_VALUE;
        network.handoffTimeoutMs = Long.MAX_VALUE;
        network.replication.hashProbeIntervalSec = 0;
        network.replication.hashProbeChunksPerTick = Integer.MAX_VALUE;
        network.replication.diffWindowSize = 0;
        network.replication.resyncTimeoutSec = -1;
        network.replication.maxQueuedDiffsPerPeer = 0;
        network.replication.captureSnapshotIntervalTicks = 0;
        network.replication.captureMaxQueuedDiffsPerChunk = 0;

        network.normalizeRuntimeBounds();

        assertEquals(NetworkConfig.DEFAULT_LISTEN_PORT, network.listenPort);
        assertEquals(NetworkConfig.MAX_HANDOFF_TIMEOUT_MS, network.handoffTimeoutMs);
        assertEquals(1, network.replication.hashProbeIntervalSec);
        assertEquals(1024, network.replication.hashProbeChunksPerTick);
        assertEquals(1, network.replication.diffWindowSize);
        assertEquals(0, network.replication.resyncTimeoutSec);
        assertEquals(1, network.replication.maxQueuedDiffsPerPeer);
        assertEquals(20, network.replication.captureSnapshotIntervalTicks);
        assertEquals(16, network.replication.captureMaxQueuedDiffsPerChunk);
    }

    @Test
    void settingsSnapshotNormalizesMissingAndLowNetworkBounds() {
        String source = """
            schema = 3

            [network]
            listen-port = -1
            handoff-timeout-ms = 0

            [network.replication]
            hash-probe-interval-sec = -5
            hash-probe-chunks-per-tick = -5
            diff-window-size = -5
            resync-timeout-sec = -5
            max-queued-diffs-per-peer = -5
            capture-snapshot-interval-ticks = -5
            capture-max-queued-diffs-per-chunk = -5
            """;

        WormholesSettings settings = WormholesSettings.loadSnapshot(source.getBytes(StandardCharsets.UTF_8));
        NetworkConfig network = settings.getNetwork();

        assertEquals(NetworkConfig.DEFAULT_LISTEN_PORT, network.listenPort);
        assertEquals(NetworkConfig.MIN_HANDOFF_TIMEOUT_MS, network.handoffTimeoutMs);
        assertEquals(1, network.replication.hashProbeIntervalSec);
        assertEquals(1, network.replication.hashProbeChunksPerTick);
        assertEquals(1, network.replication.diffWindowSize);
        assertEquals(0, network.replication.resyncTimeoutSec);
        assertEquals(1, network.replication.maxQueuedDiffsPerPeer);
        assertEquals(20, network.replication.captureSnapshotIntervalTicks);
        assertEquals(16, network.replication.captureMaxQueuedDiffsPerChunk);
    }

    @Test
    void gameHostsNormalizeBeforeSettingsArePublished() {
        String source = """
            schema = 3

            [network]
            advertise-host-override = " Wire.Example "
            game-host-override = " Play.Example "
            private-game-host-override = "[::1]"
            """;

        NetworkConfig network = WormholesSettings.loadSnapshot(source.getBytes(StandardCharsets.UTF_8)).getNetwork();

        assertEquals("wire.example", network.advertiseHostOverride);
        assertEquals("play.example", network.gameHostOverride);
        assertEquals("::1", network.privateGameHostOverride);
        assertEquals(0, network.gamePortOverride);
        assertEquals(0, network.privateGamePortOverride);
    }

    @Test
    void invalidGameHostsAreRejectedDuringSettingsLoad() {
        for (String key : List.of("advertise-host-override", "game-host-override", "private-game-host-override")) {
            for (String host : List.of("bad host", "example.com:25565", "0.0.0.0", "::", "239.1.2.3")) {
                String source = "schema = 3\n[network]\n" + key + " = \"" + host + "\"\n";
                assertThrows(IllegalArgumentException.class,
                    () -> WormholesSettings.loadSnapshot(source.getBytes(StandardCharsets.UTF_8)), key + ": " + host);
            }
        }
    }

    @Test
    void malformedClientRoutesAreRejectedDuringSettingsLoad() {
        for (String cidr : List.of("10.0.0.0/33", "2001:db8::/129", "client.example/24", "10.0.0.0", "10.0.0.0/no")) {
            String source = """
                schema = 3
                [[network.client-routes]]
                server = "beta"
                client-cidr = "%s"
                host = "play.example"
                port = 25566
                """.formatted(cidr);
            assertThrows(IllegalArgumentException.class,
                () -> WormholesSettings.loadSnapshot(source.getBytes(StandardCharsets.UTF_8)), cidr);
        }
        String invalidDestination = """
            schema = 3
            [[network.client-routes]]
            server = "beta"
            client-cidr = "10.0.0.0/8"
            host = "bad host"
            port = 25566
            """;
        assertThrows(IllegalArgumentException.class,
            () -> WormholesSettings.loadSnapshot(invalidDestination.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void missingReplicationSectionRestoresDefaults() {
        NetworkConfig network = new NetworkConfig();
        network.replication = null;

        network.normalizeRuntimeBounds();

        assertNotNull(network.replication);
        assertEquals(4096, network.replication.maxQueuedDiffsPerPeer);
    }

    @Test
    void proxyDestinationsNormalizeAtLoadAndOnlyOverrideAutoMode() {
        String source = """
            schema = 3

            [network]
            proxy-servers = [" Beta-Survival ", "creative"]
            """;
        NetworkConfig network = WormholesSettings.loadSnapshot(source.getBytes(StandardCharsets.UTF_8)).getNetwork();

        assertEquals(List.of("Beta-Survival", "creative"), network.proxyServers);
        assertEquals("proxy", network.effectiveTransferMode("beta-survival", "auto"));
        assertEquals("proxy", network.effectiveTransferMode("CREATIVE", null));
        assertEquals("direct", network.effectiveTransferMode("Beta-Survival", "direct"));
        assertEquals("proxy", network.effectiveTransferMode("unlisted", "proxy"));
        assertEquals("auto", network.effectiveTransferMode("unlisted", "auto"));
    }

    @Test
    void emptyProxyDestinationIsRejectedAtLoad() {
        String source = """
            schema = 3

            [network]
            proxy-servers = ["beta", " "]
            """;

        assertThrows(IllegalArgumentException.class,
            () -> WormholesSettings.loadSnapshot(source.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void missingProxyDestinationsRestoreAnEmptyList() {
        NetworkConfig network = new NetworkConfig();
        network.proxyServers = null;

        network.normalizeRuntimeBounds();

        assertEquals(List.of(), network.proxyServers);
        assertEquals("auto", network.effectiveTransferMode("beta", "auto"));
    }
}
