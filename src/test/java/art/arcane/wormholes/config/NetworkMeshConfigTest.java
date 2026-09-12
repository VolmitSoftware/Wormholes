package art.arcane.wormholes.config;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.WireCapability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkMeshConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void freshInstallEmitsMeshPolicyAndProxyTables() throws IOException {
        WormholesSettings settings = WormholesSettings.loadAll(tempDir);
        List<String> emitted = Files.readAllLines(tempDir.resolve(WormholesSettings.CONFIG_FILE_NAME), StandardCharsets.UTF_8).stream()
            .map(String::trim)
            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
            .toList();

        assertTrue(emitted.contains("[network.mesh]"));
        assertTrue(emitted.contains("introducers = \"trusted\""));
        assertTrue(emitted.contains("auto-accept-introductions = false"));
        assertTrue(emitted.contains("[network.policy]"));
        assertTrue(emitted.contains("queue-max-wait-sec = 25"));
        assertTrue(emitted.contains("[network.proxy]"));
        assertTrue(emitted.contains("channel = \"wormholes:proxy\""));
        assertTrue(emitted.contains("plugin-version-policy = \"compatible\""));
        assertTrue(emitted.contains("directory-cache-enabled = true"));

        NetworkConfig network = settings.getNetwork();
        assertTrue(network.mesh.enabled);
        assertEquals(60, network.mesh.announceIntervalSec);
        assertEquals(30, network.mesh.announceRateLimitPerSourcePerMin);
        assertEquals(30, network.mesh.tombstoneTtlDays);
        assertTrue(network.mesh.introducerAllowlist.isEmpty());
        assertEquals(5, network.policy.beaconIntervalSec);
        assertEquals(20, network.policy.beaconStaleSec);
        assertTrue(network.policy.queueEnabled);
        assertFalse(network.proxy.enabled);
    }

    @Test
    void meshBoundsNormalizeAndUnknownPoliciesAreRejected() {
        NetworkConfig network = new NetworkConfig();
        network.mesh.introducers = "Allowlist";
        network.mesh.announceIntervalSec = 0;
        network.mesh.announceRateLimitPerSourcePerMin = 0;
        network.mesh.tombstoneTtlDays = 0;
        network.policy.beaconIntervalSec = 0;
        network.policy.beaconStaleSec = 0;
        network.policy.queueMaxWaitSec = 90;
        network.pluginVersionPolicy = "EXACT";

        network.normalizeRuntimeBounds();

        assertEquals("allowlist", network.mesh.introducers);
        assertEquals(NetworkConfig.MeshConfig.MIN_ANNOUNCE_INTERVAL_SEC, network.mesh.announceIntervalSec);
        assertEquals(1, network.mesh.announceRateLimitPerSourcePerMin);
        assertEquals(1, network.mesh.tombstoneTtlDays);
        assertEquals(NetworkConfig.PolicyConfig.MIN_BEACON_INTERVAL_SEC, network.policy.beaconIntervalSec);
        assertEquals(NetworkConfig.PolicyConfig.MIN_BEACON_INTERVAL_SEC * 2, network.policy.beaconStaleSec);
        assertEquals(NetworkConfig.PolicyConfig.MAX_QUEUE_WAIT_SEC, network.policy.queueMaxWaitSec);
        assertEquals("exact", network.pluginVersionPolicy);

        NetworkConfig badIntroducers = new NetworkConfig();
        badIntroducers.mesh.introducers = "everyone";
        assertThrows(IllegalArgumentException.class, badIntroducers::normalizeRuntimeBounds);

        NetworkConfig badVersionPolicy = new NetworkConfig();
        badVersionPolicy.pluginVersionPolicy = "loose";
        assertThrows(IllegalArgumentException.class, badVersionPolicy::normalizeRuntimeBounds);
    }

    @Test
    void meshCapabilityBitsSitInsideTheReservedRangeAndAreLocallyAdvertised() {
        assertEquals(8, WireCapability.MESH_ANNOUNCE.bit());
        assertEquals(9, WireCapability.LOAD_BEACON.bit());
        assertEquals(10, WireCapability.PORTAL_QUERY.bit());
        assertEquals(11, WireCapability.HANDOFF_QUEUE.bit());
        assertEquals(12, WireCapability.VERSIONED_TRANSCRIPT.bit());
        long local = WireCapability.localSet();
        assertTrue(WireCapability.MESH_ANNOUNCE.in(local));
        assertTrue(WireCapability.LOAD_BEACON.in(local));
        assertTrue(WireCapability.PORTAL_QUERY.in(local));
        assertTrue(WireCapability.HANDOFF_QUEUE.in(local));
        assertTrue(WireCapability.VERSIONED_TRANSCRIPT.in(local));
    }
}
