package art.arcane.optics.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ViewStreamSessionInboundTest {
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private static byte[] viewStats(int overlayCells) throws ViewStreamProtocolException {
        return ViewStreamFixtures.CODEC.encodeC2S(new ViewStreamMessage.ViewStats(10, 2, overlayCells, 0, 80, 120, 3));
    }

    @Test
    void viewStatsArrivingSlightlyEarlyAfterTransportJitterAreAccepted() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        harness.handshake(SessionHarness.CLIENT_CAPS);
        assertEquals(ViewStreamInbound.HANDLED, harness.c2s(viewStats(400)));
        long earlyMillis = ViewStreamLimits.VIEW_STATS_MIN_INTERVAL_MILLIS - 60L;
        harness.clock.addAndGet(earlyMillis * NANOS_PER_MILLI - SessionHarness.C2S_SPACING_NANOS);
        assertEquals(ViewStreamInbound.HANDLED, harness.c2s(viewStats(500)));
        ViewStreamSessionStats stats = harness.session.stats();
        assertEquals(500, stats.viewStats().overlayCells());
        assertEquals(0L, stats.c2sDropped());
        assertEquals(0L, stats.c2sStale());
    }

    @Test
    void viewStatsRepeatedInsideTheToleranceAreStaleNotViolations() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        harness.handshake(SessionHarness.CLIENT_CAPS);
        assertEquals(ViewStreamInbound.HANDLED, harness.c2s(viewStats(400)));
        assertEquals(ViewStreamInbound.IGNORED, harness.c2s(viewStats(500)));
        ViewStreamSessionStats stats = harness.session.stats();
        assertEquals(400, stats.viewStats().overlayCells());
        assertEquals(1L, stats.c2sStale());
        assertEquals(0L, stats.c2sDropped());
    }

    @Test
    void messagesFromBeforeTheSessionEndedAreStaleNotViolations() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        harness.handshake(SessionHarness.CLIENT_CAPS);
        harness.session.end(ViewStreamMessage.ResetReason.DISABLED);
        harness.pump();
        assertEquals(ViewStreamSessionState.VANILLA, harness.session.state());
        assertEquals(ViewStreamInbound.IGNORED, harness.c2s(ViewStreamFixtures.CODEC.encodeC2S(new ViewStreamMessage.Ack(3, 40, 12))));
        assertEquals(ViewStreamInbound.IGNORED, harness.c2s(ViewStreamFixtures.CODEC.encodeC2S(
            ViewStreamMessage.BrickMiss.of(new ViewStreamMessage.BrickMiss.Plate(1, 1, new long[] {-1L})))));
        assertEquals(ViewStreamInbound.IGNORED, harness.c2s(ViewStreamFixtures.CODEC.encodeC2S(new ViewStreamMessage.PlateRefused(1, 1))));
        assertEquals(ViewStreamInbound.IGNORED, harness.c2s(viewStats(400)));
        ViewStreamSessionStats stats = harness.session.stats();
        assertEquals(4L, stats.c2sStale());
        assertEquals(0L, stats.c2sDropped());
        byte[] garbage = {(byte) 99, 1, 2};
        assertEquals(ViewStreamInbound.DROPPED, harness.c2s(garbage));
        assertEquals(1L, harness.session.stats().c2sDropped());
        assertEquals(4L, harness.session.stats().c2sStale());
    }

    @Test
    void aSecondHelloAfterAcceptanceIsStaleNotAViolation() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        harness.handshake(SessionHarness.CLIENT_CAPS);
        assertEquals(ViewStreamInbound.IGNORED, harness.c2s(harness.client.hello(SessionHarness.DATA_VERSION, SessionHarness.CLIENT_CAPS, "fabric", 0L)));
        ViewStreamSessionStats stats = harness.session.stats();
        assertEquals(1L, stats.c2sStale());
        assertEquals(0L, stats.c2sDropped());
        assertEquals(ViewStreamSessionState.CLIENT_VIEW, stats.state());
    }

    @Test
    void malformedResetLogsItsReasonAndDecoderFailureExactlyOnce() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        harness.handshake(SessionHarness.CLIENT_CAPS);
        byte[] invalid = new byte[]{99, 1, 2};
        assertEquals(ViewStreamInbound.DROPPED, harness.c2s(invalid));
        assertEquals(ViewStreamInbound.DROPPED, harness.c2s(invalid));
        assertTrue(harness.warnings.isEmpty());
        assertEquals(ViewStreamInbound.RESET, harness.c2s(invalid));
        assertEquals(1, harness.warnings.size());
        assertTrue(harness.warnings.getFirst().getMessage().contains("PROTOCOL, packet UNKNOWN(99), bytes 3"));
        assertInstanceOf(ViewStreamProtocolException.class, harness.warnings.getFirst().getCause());
        assertEquals(ViewStreamSessionState.VANILLA, harness.session.state());
    }
}
