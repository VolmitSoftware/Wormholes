package art.arcane.wormholes.papi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WormholesRuntimeSnapshotTest {
    @Test
    void linkIsOfflineWhenThereIsNoNetworkManagerOrItIsNotRunning() {
        assertEquals(WormholesRuntimeSnapshot.LINK_OFFLINE, WormholesRuntimeSnapshot.link(false, false, 0, 0));
        assertEquals(WormholesRuntimeSnapshot.LINK_OFFLINE, WormholesRuntimeSnapshot.link(false, true, 3, 3));
        assertEquals(WormholesRuntimeSnapshot.LINK_OFFLINE, WormholesRuntimeSnapshot.link(true, false, 3, 3));
    }

    @Test
    void linkDistinguishesSoloDownDegradedAndLinked() {
        assertEquals(WormholesRuntimeSnapshot.LINK_SOLO, WormholesRuntimeSnapshot.link(true, true, 0, 0));
        assertEquals(WormholesRuntimeSnapshot.LINK_DOWN, WormholesRuntimeSnapshot.link(true, true, 0, 2));
        assertEquals(WormholesRuntimeSnapshot.LINK_DEGRADED, WormholesRuntimeSnapshot.link(true, true, 1, 2));
        assertEquals(WormholesRuntimeSnapshot.LINK_LINKED, WormholesRuntimeSnapshot.link(true, true, 2, 2));
        assertEquals(WormholesRuntimeSnapshot.LINK_LINKED, WormholesRuntimeSnapshot.link(true, true, 3, 2));
    }

    @Test
    void snapshotPreformatsEveryValueItWillEverServe() {
        WormholesRuntimeSnapshot snapshot = WormholesRuntimeSnapshot.of(17, 4, 9, true, true, 1, 2, 3, 812L, 6.5D);

        assertEquals("17", snapshot.portals());
        assertEquals("4", snapshot.projectionsActive());
        assertEquals("9", snapshot.projectionsObservers());
        assertEquals("1", snapshot.peersConnected());
        assertEquals(WormholesRuntimeSnapshot.LINK_DEGRADED, snapshot.peersLink());
        assertEquals("3", snapshot.transfersInFlight());
        assertEquals("812", snapshot.failures());
        assertEquals("6.50", snapshot.failuresPerMinute());
    }

    @Test
    void countsAreEmittedWithoutGroupingSeparatorsSoConsumersCanParseThem() {
        WormholesRuntimeSnapshot snapshot = WormholesRuntimeSnapshot.of(12_500, 0, 0, false, false, 0, 0, 0, 1_234_567L, 1_234.5D);

        assertEquals("12500", snapshot.portals());
        assertEquals("1234567", snapshot.failures());
        assertEquals("1234.50", snapshot.failuresPerMinute());
    }

    @Test
    void noValueEverCarriesAPercentSign() {
        WormholesRuntimeSnapshot snapshot = WormholesRuntimeSnapshot.of(1, 2, 3, true, true, 4, 4, 5, 6L, 7.0D);

        for (String value : new String[]{snapshot.portals(), snapshot.projectionsActive(), snapshot.projectionsObservers(),
            snapshot.peersConnected(), snapshot.peersLink(), snapshot.transfersInFlight(), snapshot.failures(), snapshot.failuresPerMinute()}) {
            assertFalse(value.contains("%"));
        }
    }
}
