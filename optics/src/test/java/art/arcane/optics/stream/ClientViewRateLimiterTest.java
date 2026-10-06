package art.arcane.optics.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ClientViewRateLimiterTest {
    private static final int CLIENT_TICKS_PER_SECOND = 20;
    private static final int MESSAGES_PER_CLIENT_TICK = 2;
    private static final int CATCH_UP_TICKS = 10;

    @Test
    void messagesUpToTheCapPassAndTheNextOneIsDropped() {
        ClientViewRateLimiter limiter = new ClientViewRateLimiter();
        int cap = ViewStreamLimits.MAX_C2S_MESSAGES_PER_SECOND;
        for (int i = 0; i < cap; i++) {
            assertEquals(ClientViewRateLimiter.Verdict.ACCEPT, limiter.admit(1000L, 16, -1), "message " + i);
        }
        assertEquals(ClientViewRateLimiter.Verdict.DROP, limiter.admit(1600L, 16, -1));
        assertEquals(ClientViewRateLimiter.Verdict.ACCEPT, limiter.admit(2001L, 16, -1));
        assertEquals(1L, limiter.dropped());
        assertEquals(cap + 1L, limiter.admitted());
    }

    @Test
    void theCapLeavesRoomForAnAckAndABrickMissEveryClientTickPlusACatchUpBurst() {
        ClientViewRateLimiter limiter = new ClientViewRateLimiter();
        long now = 0L;
        for (int tick = 0; tick < CLIENT_TICKS_PER_SECOND * 3; tick++) {
            boolean burst = tick >= CLIENT_TICKS_PER_SECOND && tick < CLIENT_TICKS_PER_SECOND + CATCH_UP_TICKS;
            now = burst ? now : now + 50L;
            for (int message = 0; message < MESSAGES_PER_CLIENT_TICK; message++) {
                assertEquals(ClientViewRateLimiter.Verdict.ACCEPT, limiter.admit(now, 16, -1), "tick " + tick);
            }
        }
        assertTrue(ViewStreamLimits.MAX_C2S_MESSAGES_PER_SECOND >= (CLIENT_TICKS_PER_SECOND + CATCH_UP_TICKS) * MESSAGES_PER_CLIENT_TICK);
        assertEquals(0L, limiter.dropped());
    }

    @Test
    void meshAndFrameAcknowledgementsWithAnInFlightBurstStayWithinTheBound() {
        ClientViewRateLimiter limiter = new ClientViewRateLimiter();
        for (int tick = 0; tick < 40; tick++) {
            int messages = 16 + (tick == 19 ? 64 : 0);
            for (int message = 0; message < messages; message++) {
                assertEquals(ClientViewRateLimiter.Verdict.ACCEPT, limiter.admit(tick * 50L, 32, -1));
            }
        }
        assertEquals(0, limiter.dropped());
    }

    @Test
    void oversizePayloadsAreViolationsAndThreeInTenSecondsReset() {
        ClientViewRateLimiter limiter = new ClientViewRateLimiter();
        assertEquals(ClientViewRateLimiter.Verdict.DROP, limiter.admit(0L, ViewStreamLimits.MAX_C2S_BYTES + 1, -1));
        assertEquals(ClientViewRateLimiter.Verdict.DROP, limiter.admit(4000L, ViewStreamLimits.MAX_C2S_BYTES + 1, -1));
        assertEquals(ClientViewRateLimiter.Verdict.RESET, limiter.admit(9999L, ViewStreamLimits.MAX_C2S_BYTES + 1, -1));
        assertEquals(ClientViewRateLimiter.Verdict.ACCEPT, limiter.admit(10000L, 1, -1));
    }

    @Test
    void violationsOutsideTheWindowDoNotAccumulate() {
        ClientViewRateLimiter limiter = new ClientViewRateLimiter(20, 100, 3, 10_000L);
        assertEquals(ClientViewRateLimiter.Verdict.DROP, limiter.violation(0L, -1, 0));
        assertEquals(ClientViewRateLimiter.Verdict.DROP, limiter.violation(5000L, -1, 0));
        assertEquals(ClientViewRateLimiter.Verdict.DROP, limiter.violation(10_001L, -1, 0));
        assertEquals(ClientViewRateLimiter.Verdict.DROP, limiter.violation(15_002L, -1, 0));
        assertEquals(ClientViewRateLimiter.Verdict.RESET, limiter.violation(15_003L, -1, 0));
    }

    @Test
    void rateResetPreservesExactAcceptedPacketMixBeforeClearingItsWindow() {
        ClientViewRateLimiter limiter = new ClientViewRateLimiter(3, 100, 3, 10_000L);
        assertEquals(ClientViewRateLimiter.Verdict.ACCEPT, limiter.admit(1000L, 16, ViewStreamMessageType.ACK.id()));
        assertEquals(ClientViewRateLimiter.Verdict.ACCEPT, limiter.admit(1000L, 70, ViewStreamMessageType.TRAVEL_CACHED.id()));
        assertEquals(ClientViewRateLimiter.Verdict.ACCEPT, limiter.admit(1000L, 70, ViewStreamMessageType.TRAVEL_CACHED.id()));
        assertEquals(ClientViewRateLimiter.Verdict.DROP, limiter.admit(1000L, 16, ViewStreamMessageType.MESH_ACK.id()));
        assertEquals(ClientViewRateLimiter.Verdict.DROP, limiter.admit(1000L, 16, ViewStreamMessageType.MESH_ACK.id()));
        assertEquals(ClientViewRateLimiter.Verdict.RESET, limiter.admit(1000L, 16, ViewStreamMessageType.MESH_ACK.id()));
        assertEquals("MESSAGE_RATE, packet MESH_ACK, bytes 16, accepted in last second 3, by type ACK=1 TRAVEL_CACHED=2",
            limiter.lastViolation());
        assertEquals(ClientViewRateLimiter.Verdict.ACCEPT, limiter.admit(1000L, 16, ViewStreamMessageType.ACK.id()));
    }

    @Test
    void sizeResetIdentifiesThePacketWithoutCountingItAsAcceptedTraffic() {
        ClientViewRateLimiter limiter = new ClientViewRateLimiter(3, 100, 3, 10_000L);
        assertEquals(ClientViewRateLimiter.Verdict.DROP, limiter.admit(1000L, 101, ViewStreamMessageType.TRAVEL_CACHED.id()));
        assertEquals(ClientViewRateLimiter.Verdict.DROP, limiter.admit(1000L, 101, ViewStreamMessageType.TRAVEL_CACHED.id()));
        assertEquals(ClientViewRateLimiter.Verdict.RESET, limiter.admit(1000L, 101, ViewStreamMessageType.TRAVEL_CACHED.id()));
        assertEquals("PAYLOAD_SIZE, packet TRAVEL_CACHED, bytes 101, accepted in last second 0, by type", limiter.lastViolation());
    }
}
