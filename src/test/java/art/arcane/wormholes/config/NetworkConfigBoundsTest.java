package art.arcane.wormholes.config;

import art.arcane.wormholes.config.toml.NetworkConfig;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
            schema = 2

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
    void missingReplicationSectionRestoresDefaults() {
        NetworkConfig network = new NetworkConfig();
        network.replication = null;

        network.normalizeRuntimeBounds();

        assertNotNull(network.replication);
        assertEquals(4096, network.replication.maxQueuedDiffsPerPeer);
    }
}
