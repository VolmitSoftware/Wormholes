package art.arcane.wormholes.network.mesh;

import art.arcane.wormholes.network.WireCapability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerLoadTableTest {
    private static LoadBeacon beacon(int online, int max, boolean drain) {
        return new LoadBeacon(online, max, 0, 20.0D, 12.5D, drain, WireCapability.localSet(), 0L);
    }

    @Test
    void latestBeaconIsKeptPerPeerAndGoesStaleAfterTheConfiguredWindow() {
        PeerLoadTable table = new PeerLoadTable();
        table.record("beta", beacon(3, 20, false), 1_000L);
        table.record("beta", beacon(5, 20, false), 2_000L);
        assertEquals(5, table.latest("beta").online());
        assertFalse(table.isStale("beta", 21_000L, 20_000L));
        assertTrue(table.isStale("beta", 22_001L, 20_000L));
        assertTrue(table.isStale("unknown", 0L, 20_000L));
        assertNull(table.latest("unknown"));
    }

    @Test
    void headroomAndDrainReadThroughAndForgetDropsThePeer() {
        PeerLoadTable table = new PeerLoadTable();
        table.record("beta", new LoadBeacon(18, 20, 1, 19.0D, 40.0D, false, 0L, 0L), 1_000L);
        assertEquals(1, table.latest("beta").headroom());
        assertFalse(table.latest("beta").drain());
        table.record("gamma", beacon(0, 0, true), 1_000L);
        assertEquals(Integer.MAX_VALUE, table.latest("gamma").headroom());
        assertTrue(table.latest("gamma").drain());
        assertEquals(2, table.peers().size());
        table.forget("beta");
        assertNull(table.latest("beta"));
        assertEquals(1, table.peers().size());
    }
}
