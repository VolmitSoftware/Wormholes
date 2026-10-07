package art.arcane.optics.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.optics.internal.stream.ViewStreamRateLimiter;
import org.junit.jupiter.api.Test;

final class ViewStreamRateLimiterTest {
    private static final int CLIENT_TICKS_PER_SECOND = 20;
    private static final int MESSAGES_PER_CLIENT_TICK = 2;
    private static final int CATCH_UP_TICKS = 10;

    @Test
    void messagesUpToTheCapPassAndTheNextOneIsDropped() {
        ViewStreamRateLimiter limiter = new ViewStreamRateLimiter(ViewStreamFixtures.CODEC::name);
        int cap = ViewStreamLimits.MAX_C2S_MESSAGES_PER_SECOND;
        for (int i = 0; i < cap; i++) {
            assertEquals(ViewStreamRateLimiter.Verdict.ACCEPT, limiter.admit(1000L, 16, -1), "message " + i);
        }
        assertEquals(ViewStreamRateLimiter.Verdict.DROP, limiter.admit(1600L, 16, -1));
        assertEquals(ViewStreamRateLimiter.Verdict.ACCEPT, limiter.admit(2001L, 16, -1));
        assertEquals(1L, limiter.dropped());
        assertEquals(cap + 1L, limiter.admitted());
    }

    @Test
    void theCapLeavesRoomForAnAckAndABrickMissEveryClientTickPlusACatchUpBurst() {
        ViewStreamRateLimiter limiter = new ViewStreamRateLimiter(ViewStreamFixtures.CODEC::name);
        long now = 0L;
        for (int tick = 0; tick < CLIENT_TICKS_PER_SECOND * 3; tick++) {
            boolean burst = tick >= CLIENT_TICKS_PER_SECOND && tick < CLIENT_TICKS_PER_SECOND + CATCH_UP_TICKS;
            now = burst ? now : now + 50L;
            for (int message = 0; message < MESSAGES_PER_CLIENT_TICK; message++) {
                assertEquals(ViewStreamRateLimiter.Verdict.ACCEPT, limiter.admit(now, 16, -1), "tick " + tick);
            }
        }
        assertTrue(ViewStreamLimits.MAX_C2S_MESSAGES_PER_SECOND >= (CLIENT_TICKS_PER_SECOND + CATCH_UP_TICKS) * MESSAGES_PER_CLIENT_TICK);
        assertEquals(0L, limiter.dropped());
    }

    @Test
    void meshAndFrameAcknowledgementsWithAnInFlightBurstStayWithinTheBound() {
        ViewStreamRateLimiter limiter = new ViewStreamRateLimiter(ViewStreamFixtures.CODEC::name);
        for (int tick = 0; tick < 40; tick++) {
            int messages = 16 + (tick == 19 ? 64 : 0);
            for (int message = 0; message < messages; message++) {
                assertEquals(ViewStreamRateLimiter.Verdict.ACCEPT, limiter.admit(tick * 50L, 32, -1));
            }
        }
        assertEquals(0, limiter.dropped());
    }

    @Test
    void oversizePayloadsAreViolationsAndThreeInTenSecondsReset() {
        ViewStreamRateLimiter limiter = new ViewStreamRateLimiter(ViewStreamFixtures.CODEC::name);
        assertEquals(ViewStreamRateLimiter.Verdict.DROP, limiter.admit(0L, ViewStreamLimits.MAX_C2S_BYTES + 1, -1));
        assertEquals(ViewStreamRateLimiter.Verdict.DROP, limiter.admit(4000L, ViewStreamLimits.MAX_C2S_BYTES + 1, -1));
        assertEquals(ViewStreamRateLimiter.Verdict.RESET, limiter.admit(9999L, ViewStreamLimits.MAX_C2S_BYTES + 1, -1));
        assertEquals(ViewStreamRateLimiter.Verdict.ACCEPT, limiter.admit(10000L, 1, -1));
    }

    @Test
    void violationsOutsideTheWindowDoNotAccumulate() {
        ViewStreamRateLimiter limiter = new ViewStreamRateLimiter(20, 100, 3, 10_000L, ViewStreamFixtures.CODEC::name);
        assertEquals(ViewStreamRateLimiter.Verdict.DROP, limiter.violation(0L, -1, 0));
        assertEquals(ViewStreamRateLimiter.Verdict.DROP, limiter.violation(5000L, -1, 0));
        assertEquals(ViewStreamRateLimiter.Verdict.DROP, limiter.violation(10_001L, -1, 0));
        assertEquals(ViewStreamRateLimiter.Verdict.DROP, limiter.violation(15_002L, -1, 0));
        assertEquals(ViewStreamRateLimiter.Verdict.RESET, limiter.violation(15_003L, -1, 0));
    }

    @Test
    void rateResetPreservesExactAcceptedPacketMixBeforeClearingItsWindow() {
        ViewStreamRateLimiter limiter = new ViewStreamRateLimiter(3, 100, 3, 10_000L, ViewStreamFixtures.CODEC::name);
        assertEquals(ViewStreamRateLimiter.Verdict.ACCEPT, limiter.admit(1000L, 16, ViewStreamMessageType.ACK.id()));
        assertEquals(ViewStreamRateLimiter.Verdict.ACCEPT, limiter.admit(1000L, 70, ViewStreamMessageType.MESH_CACHED.id()));
        assertEquals(ViewStreamRateLimiter.Verdict.ACCEPT, limiter.admit(1000L, 70, ViewStreamMessageType.MESH_CACHED.id()));
        assertEquals(ViewStreamRateLimiter.Verdict.DROP, limiter.admit(1000L, 16, ViewStreamMessageType.MESH_ACK.id()));
        assertEquals(ViewStreamRateLimiter.Verdict.DROP, limiter.admit(1000L, 16, ViewStreamMessageType.MESH_ACK.id()));
        assertEquals(ViewStreamRateLimiter.Verdict.RESET, limiter.admit(1000L, 16, ViewStreamMessageType.MESH_ACK.id()));
        assertEquals("MESSAGE_RATE, packet MESH_ACK, bytes 16, accepted in last second 3, by type ACK=1 MESH_CACHED=2",
            limiter.lastViolation());
        assertEquals(ViewStreamRateLimiter.Verdict.ACCEPT, limiter.admit(1000L, 16, ViewStreamMessageType.ACK.id()));
    }

    @Test
    void sizeResetIdentifiesThePacketWithoutCountingItAsAcceptedTraffic() {
        ViewStreamRateLimiter limiter = new ViewStreamRateLimiter(3, 100, 3, 10_000L, ViewStreamFixtures.CODEC::name);
        assertEquals(ViewStreamRateLimiter.Verdict.DROP, limiter.admit(1000L, 101, ViewStreamMessageType.MESH_CACHED.id()));
        assertEquals(ViewStreamRateLimiter.Verdict.DROP, limiter.admit(1000L, 101, ViewStreamMessageType.MESH_CACHED.id()));
        assertEquals(ViewStreamRateLimiter.Verdict.RESET, limiter.admit(1000L, 101, ViewStreamMessageType.MESH_CACHED.id()));
        assertEquals("PAYLOAD_SIZE, packet MESH_CACHED, bytes 101, accepted in last second 0, by type", limiter.lastViolation());
    }
}
