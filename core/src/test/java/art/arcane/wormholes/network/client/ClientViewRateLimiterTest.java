package art.arcane.wormholes.network.client;

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
        int cap = ClientViewProtocol.MAX_C2S_MESSAGES_PER_SECOND;
        for (int i = 0; i < cap; i++) {
            assertEquals(ClientViewRateLimiter.Verdict.ACCEPT, limiter.admit(1000L + i * 5L, 16), "message " + i);
        }
        assertEquals(ClientViewRateLimiter.Verdict.DROP, limiter.admit(1600L, 16));
        assertEquals(ClientViewRateLimiter.Verdict.ACCEPT, limiter.admit(2001L, 16));
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
                assertEquals(ClientViewRateLimiter.Verdict.ACCEPT, limiter.admit(now, 16), "tick " + tick);
            }
        }
        assertTrue(ClientViewProtocol.MAX_C2S_MESSAGES_PER_SECOND >= (CLIENT_TICKS_PER_SECOND + CATCH_UP_TICKS) * MESSAGES_PER_CLIENT_TICK);
        assertEquals(0L, limiter.dropped());
    }

    @Test
    void oversizePayloadsAreViolationsAndThreeInTenSecondsReset() {
        ClientViewRateLimiter limiter = new ClientViewRateLimiter();
        assertEquals(ClientViewRateLimiter.Verdict.DROP, limiter.admit(0L, ClientViewProtocol.MAX_C2S_BYTES + 1));
        assertEquals(ClientViewRateLimiter.Verdict.DROP, limiter.admit(4000L, ClientViewProtocol.MAX_C2S_BYTES + 1));
        assertEquals(ClientViewRateLimiter.Verdict.RESET, limiter.admit(9999L, ClientViewProtocol.MAX_C2S_BYTES + 1));
        assertEquals(ClientViewRateLimiter.Verdict.ACCEPT, limiter.admit(10000L, 1));
    }

    @Test
    void violationsOutsideTheWindowDoNotAccumulate() {
        ClientViewRateLimiter limiter = new ClientViewRateLimiter(20, 100, 3, 10_000L);
        assertEquals(ClientViewRateLimiter.Verdict.DROP, limiter.violation(0L));
        assertEquals(ClientViewRateLimiter.Verdict.DROP, limiter.violation(5000L));
        assertEquals(ClientViewRateLimiter.Verdict.DROP, limiter.violation(10_001L));
        assertEquals(ClientViewRateLimiter.Verdict.DROP, limiter.violation(15_002L));
        assertEquals(ClientViewRateLimiter.Verdict.RESET, limiter.violation(15_003L));
    }
}
