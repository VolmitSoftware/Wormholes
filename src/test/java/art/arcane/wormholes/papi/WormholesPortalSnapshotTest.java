package art.arcane.wormholes.papi;

import art.arcane.volmlib.util.bukkit.papi.PlaceholderValues;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WormholesPortalSnapshotTest {
    @Test
    void stateReportsOpenClosedAndSyncing() {
        assertEquals(WormholesPortalSnapshot.STATE_OPEN, WormholesPortalSnapshot.state(true, false));
        assertEquals(WormholesPortalSnapshot.STATE_CLOSED, WormholesPortalSnapshot.state(false, false));
        assertEquals(WormholesPortalSnapshot.STATE_SYNCING, WormholesPortalSnapshot.state(true, true));
        assertEquals(WormholesPortalSnapshot.STATE_SYNCING, WormholesPortalSnapshot.state(false, true));
    }

    @Test
    void rtpStateIsUnavailableForAPortalThatIsNotRtpOrIsNotRegisteredYet() {
        assertEquals(PlaceholderValues.UNAVAILABLE, WormholesPortalSnapshot.rtpState(false, true, true, false, false, 0L));
        assertEquals(PlaceholderValues.UNAVAILABLE, WormholesPortalSnapshot.rtpState(true, false, true, false, false, 0L));
        assertEquals(PlaceholderValues.UNAVAILABLE, WormholesPortalSnapshot.rtpCooldown(false, true, 5_000L));
        assertEquals(PlaceholderValues.UNAVAILABLE, WormholesPortalSnapshot.rtpCooldown(true, false, 5_000L));
    }

    @Test
    void rtpStateRanksRerollingOverWarmingOverReadyOverCooldown() {
        assertEquals(WormholesPortalSnapshot.RTP_REROLLING, WormholesPortalSnapshot.rtpState(true, true, true, true, true, 9_000L));
        assertEquals(WormholesPortalSnapshot.RTP_WARMING, WormholesPortalSnapshot.rtpState(true, true, true, true, false, 9_000L));
        assertEquals(WormholesPortalSnapshot.RTP_READY, WormholesPortalSnapshot.rtpState(true, true, true, false, false, 9_000L));
        assertEquals(WormholesPortalSnapshot.RTP_COOLDOWN, WormholesPortalSnapshot.rtpState(true, true, false, false, false, 9_000L));
        assertEquals(WormholesPortalSnapshot.RTP_IDLE, WormholesPortalSnapshot.rtpState(true, true, false, false, false, 0L));
    }

    @Test
    void rtpCooldownIsReportedInSecondsAndNeverNegative() {
        assertEquals("9.00", WormholesPortalSnapshot.rtpCooldown(true, true, 9_000L));
        assertEquals("0.25", WormholesPortalSnapshot.rtpCooldown(true, true, 250L));
        assertEquals("0.00", WormholesPortalSnapshot.rtpCooldown(true, true, -40_000L));
    }

    @Test
    void snapshotPreformatsEveryValueItWillEverServe() {
        WormholesPortalSnapshot snapshot = WormholesPortalSnapshot.of(
            "Hub Gate",
            true,
            false,
            "Mining Gate",
            false,
            12.3456D,
            false,
            false,
            false,
            false,
            false,
            0L);

        assertEquals("Hub Gate", snapshot.name());
        assertEquals(WormholesPortalSnapshot.STATE_OPEN, snapshot.state());
        assertEquals("Mining Gate", snapshot.destination());
        assertEquals("12.35", snapshot.distance());
        assertEquals(PlaceholderValues.FALSE, snapshot.crossServer());
        assertEquals(PlaceholderValues.UNAVAILABLE, snapshot.rtpState());
        assertEquals(PlaceholderValues.UNAVAILABLE, snapshot.rtpCooldown());
    }

    @Test
    void snapshotMarksACrossServerDestinationAndAnRtpPortalWarming() {
        WormholesPortalSnapshot snapshot = WormholesPortalSnapshot.of(
            "Mesh Gate",
            true,
            true,
            "beta",
            true,
            0.0D,
            true,
            true,
            false,
            true,
            false,
            2_500L);

        assertEquals("beta", snapshot.destination());
        assertEquals(PlaceholderValues.TRUE, snapshot.crossServer());
        assertEquals(WormholesPortalSnapshot.STATE_SYNCING, snapshot.state());
        assertEquals(WormholesPortalSnapshot.RTP_WARMING, snapshot.rtpState());
        assertEquals("2.50", snapshot.rtpCooldown());
        assertEquals("0.00", snapshot.distance());
    }

    @Test
    void anUnlinkedPortalReportsAnUnavailableDestinationRatherThanBlankText() {
        WormholesPortalSnapshot snapshot = WormholesPortalSnapshot.of(
            "",
            false,
            false,
            null,
            false,
            3.0D,
            false,
            false,
            false,
            false,
            false,
            0L);

        assertEquals(PlaceholderValues.UNAVAILABLE, snapshot.name());
        assertEquals(PlaceholderValues.UNAVAILABLE, snapshot.destination());
    }

    @Test
    void anOwnerNamedPortalCanNeverInjectAPercentOrALegacyColourCode() {
        WormholesPortalSnapshot snapshot = WormholesPortalSnapshot.of(
            "%vault_eco_balance% §cRed",
            true,
            false,
            "%player_name%",
            false,
            1.0D,
            false,
            false,
            false,
            false,
            false,
            0L);

        assertFalse(snapshot.name().contains("%"));
        assertFalse(snapshot.name().contains("§"));
        assertFalse(snapshot.destination().contains("%"));
    }
}
