package art.arcane.wormholes.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.menu.protocol.MenuRateLimiter;

final class MenuRateLimiterTest {
    @Test
    void framesWithinTheWindowAreAdmittedUpToTheCap() {
        MenuRateLimiter limiter = new MenuRateLimiter();
        for (int index = 0; index < MenuLimits.MAX_FRAMES_PER_SECOND; index++) {
            assertEquals(MenuRateLimiter.Verdict.ACCEPT, limiter.admit(1_000L, 10));
        }

        assertEquals(MenuRateLimiter.Verdict.DROP, limiter.admit(1_500L, 10));
        assertEquals(MenuRateLimiter.Verdict.ACCEPT, limiter.admit(2_000L, 10));
    }

    @Test
    void oversizedFramesAreViolations() {
        MenuRateLimiter limiter = new MenuRateLimiter();

        assertEquals(MenuRateLimiter.Verdict.DROP, limiter.admit(0L, MenuLimits.MAX_C2S_BYTES + 1));
        assertTrue(limiter.summary().contains("exceeds"));
    }

    @Test
    void threeViolationsInsideTheWindowReset() {
        MenuRateLimiter limiter = new MenuRateLimiter();

        assertEquals(MenuRateLimiter.Verdict.DROP, limiter.violation(0L, "one"));
        assertEquals(MenuRateLimiter.Verdict.DROP, limiter.violation(4_000L, "two"));
        assertEquals(MenuRateLimiter.Verdict.RESET, limiter.violation(9_999L, "three"));
        assertTrue(limiter.summary().contains("three"));
    }

    @Test
    void violationsOutsideTheWindowDoNotReset() {
        MenuRateLimiter limiter = new MenuRateLimiter();

        assertEquals(MenuRateLimiter.Verdict.DROP, limiter.violation(0L, "one"));
        assertEquals(MenuRateLimiter.Verdict.DROP, limiter.violation(6_000L, "two"));
        assertEquals(MenuRateLimiter.Verdict.DROP, limiter.violation(12_000L, "three"));
        assertEquals(MenuRateLimiter.Verdict.RESET, limiter.violation(13_000L, "four"));
    }
}
