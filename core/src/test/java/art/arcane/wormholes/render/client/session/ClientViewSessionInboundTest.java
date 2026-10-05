package art.arcane.wormholes.render.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.network.client.ClientViewCodec;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.network.client.ClientViewProtocolException;

final class ClientViewSessionInboundTest {
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private static byte[] viewStats(int overlayCells) throws ClientViewProtocolException {
        return ClientViewCodec.encodeC2S(new ClientViewMessage.ViewStats(10, 2, overlayCells, 0, 80, 120, 3));
    }

    @Test
    void viewStatsArrivingSlightlyEarlyAfterTransportJitterAreAccepted() throws ClientViewProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        harness.handshake(SessionHarness.CLIENT_CAPS);
        assertEquals(ClientViewInbound.HANDLED, harness.c2s(viewStats(400)));
        long earlyMillis = ClientViewProtocol.VIEW_STATS_MIN_INTERVAL_MILLIS - 60L;
        harness.clock.addAndGet(earlyMillis * NANOS_PER_MILLI - SessionHarness.C2S_SPACING_NANOS);
        assertEquals(ClientViewInbound.HANDLED, harness.c2s(viewStats(500)));
        ClientViewSessionStats stats = harness.session.stats();
        assertEquals(500, stats.viewStats().overlayCells());
        assertEquals(0L, stats.c2sDropped());
        assertEquals(0L, stats.c2sStale());
    }

    @Test
    void viewStatsRepeatedInsideTheToleranceAreStaleNotViolations() throws ClientViewProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        harness.handshake(SessionHarness.CLIENT_CAPS);
        assertEquals(ClientViewInbound.HANDLED, harness.c2s(viewStats(400)));
        assertEquals(ClientViewInbound.IGNORED, harness.c2s(viewStats(500)));
        ClientViewSessionStats stats = harness.session.stats();
        assertEquals(400, stats.viewStats().overlayCells());
        assertEquals(1L, stats.c2sStale());
        assertEquals(0L, stats.c2sDropped());
    }

    @Test
    void messagesFromBeforeTheSessionEndedAreStaleNotViolations() throws ClientViewProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        harness.handshake(SessionHarness.CLIENT_CAPS);
        harness.session.end(ClientViewMessage.ResetReason.DISABLED);
        harness.pump();
        assertEquals(ClientViewSessionState.VANILLA, harness.session.state());
        assertEquals(ClientViewInbound.IGNORED, harness.c2s(ClientViewCodec.encodeC2S(new ClientViewMessage.Ack(3, 40, 12))));
        assertEquals(ClientViewInbound.IGNORED, harness.c2s(ClientViewCodec.encodeC2S(
            ClientViewMessage.BrickMiss.of(new ClientViewMessage.BrickMiss.Plate(1, 1, new long[] {-1L})))));
        assertEquals(ClientViewInbound.IGNORED, harness.c2s(ClientViewCodec.encodeC2S(new ClientViewMessage.PlateRefused(1, 1))));
        assertEquals(ClientViewInbound.IGNORED, harness.c2s(viewStats(400)));
        ClientViewSessionStats stats = harness.session.stats();
        assertEquals(4L, stats.c2sStale());
        assertEquals(0L, stats.c2sDropped());
        byte[] garbage = {(byte) 99, 1, 2};
        assertEquals(ClientViewInbound.DROPPED, harness.c2s(garbage));
        assertEquals(1L, harness.session.stats().c2sDropped());
        assertEquals(4L, harness.session.stats().c2sStale());
    }

    @Test
    void aSecondHelloAfterAcceptanceIsStaleNotAViolation() throws ClientViewProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        harness.handshake(SessionHarness.CLIENT_CAPS);
        assertEquals(ClientViewInbound.IGNORED, harness.c2s(harness.client.hello(SessionHarness.DATA_VERSION, SessionHarness.CLIENT_CAPS, "fabric", 0L)));
        ClientViewSessionStats stats = harness.session.stats();
        assertEquals(1L, stats.c2sStale());
        assertEquals(0L, stats.c2sDropped());
        assertEquals(ClientViewSessionState.CLIENT_VIEW, stats.state());
    }

    @Test
    void malformedResetLogsItsReasonAndDecoderFailureExactlyOnce() throws ClientViewProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        harness.handshake(SessionHarness.CLIENT_CAPS);
        byte[] invalid = new byte[]{99, 1, 2};
        assertEquals(ClientViewInbound.DROPPED, harness.c2s(invalid));
        assertEquals(ClientViewInbound.DROPPED, harness.c2s(invalid));
        assertTrue(harness.warnings.isEmpty());
        assertEquals(ClientViewInbound.RESET, harness.c2s(invalid));
        assertEquals(1, harness.warnings.size());
        assertTrue(harness.warnings.getFirst().getMessage().contains("PROTOCOL, packet UNKNOWN(99), bytes 3"));
        assertInstanceOf(ClientViewProtocolException.class, harness.warnings.getFirst().getCause());
        assertEquals(ClientViewSessionState.VANILLA, harness.session.state());
    }
}
