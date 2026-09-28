package art.arcane.wormholes.network.mesh;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.WireCapability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginVersionPolicyTest {
    @Test
    void exactPolicyRequiresIdenticalPluginVersions() {
        PluginVersionPolicy.Verdict same = PluginVersionPolicy.check(NetworkConfig.PLUGIN_VERSION_POLICY_EXACT, "2.1.0", "2.1.0",
            WireCapability.localSet(), WireCapability.localSet());
        assertTrue(same.accepted());
        PluginVersionPolicy.Verdict different = PluginVersionPolicy.check(NetworkConfig.PLUGIN_VERSION_POLICY_EXACT, "2.1.0", "2.2.0",
            WireCapability.localSet(), WireCapability.localSet());
        assertFalse(different.accepted());
        assertEquals("Wormholes version mismatch: peer 2.2.0, local 2.1.0", different.rejection());
    }

    @Test
    void compatiblePolicyLinksAnyPeerThatSharesTheProtocol21BaselineAndReportsMissingBits() {
        long reduced = WireCapability.PROTOCOL_21.mask() | WireCapability.MESH_ANNOUNCE.mask();
        PluginVersionPolicy.Verdict verdict = PluginVersionPolicy.check(NetworkConfig.PLUGIN_VERSION_POLICY_COMPATIBLE, "2.1.0", "2.2.0",
            WireCapability.localSet(), reduced);
        assertTrue(verdict.accepted());
        assertTrue(verdict.reduced());
        assertEquals(WireCapability.localSet() & ~reduced, verdict.missingCapabilities());
        String missing = PluginVersionPolicy.describe(verdict.missingCapabilities());
        assertTrue(missing.contains("LOAD_BEACON"));
        assertTrue(missing.contains("VERSIONED_TRANSCRIPT"));
        assertFalse(missing.contains("MESH_ANNOUNCE"));

        PluginVersionPolicy.Verdict full = PluginVersionPolicy.check(NetworkConfig.PLUGIN_VERSION_POLICY_COMPATIBLE, "2.1.0", "2.2.0",
            WireCapability.localSet(), WireCapability.localSet());
        assertTrue(full.accepted());
        assertFalse(full.reduced());

        PluginVersionPolicy.Verdict baselineMissing = PluginVersionPolicy.check(NetworkConfig.PLUGIN_VERSION_POLICY_COMPATIBLE, "2.1.0", "2.2.0",
            WireCapability.localSet(), WireCapability.MESH_ANNOUNCE.mask());
        assertFalse(baselineMissing.accepted());
        assertTrue(baselineMissing.rejection().contains("PROTOCOL_21"));
    }

    @Test
    void describeNamesKnownBitsAndNumbersUnknownOnes() {
        assertEquals("none", PluginVersionPolicy.describe(0L));
        assertEquals("MESH_ANNOUNCE, bit 50", PluginVersionPolicy.describe(WireCapability.MESH_ANNOUNCE.mask() | (1L << 50)));
    }
}
