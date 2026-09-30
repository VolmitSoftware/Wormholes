package art.arcane.wormholes.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RatioWindowTest {
    @Test
    void theRatioReflectsTheLatestWindowRatherThanTheLifetimeTotals() {
        RatioWindow window = new RatioWindow();

        window.ratio(9_000L, 10_000L, 1_000L);
        assertEquals(0.3D, window.ratio(9_300L, 11_000L, 2_000L), 1.0E-9D);
        assertEquals(0.9D, window.ratio(10_200L, 12_000L, 3_000L), 1.0E-9D,
            "a dictionary going stale must show up in the next window even after a long efficient history");
    }

    @Test
    void aWindowWithoutRawTrafficHasNoRatio() {
        RatioWindow window = new RatioWindow();

        assertTrue(Double.isNaN(window.ratio(500L, 1_000L, 1_000L)));
        assertTrue(Double.isNaN(window.ratio(500L, 1_000L, 2_000L)),
            "an idle link must not read as perfect compression");
    }

    @Test
    void readsInsideTheMinimumWindowRepeatTheLastRatio() {
        RatioWindow window = new RatioWindow();

        window.ratio(0L, 0L, 1_000L);
        assertEquals(0.5D, window.ratio(50L, 100L, 2_000L), 1.0E-9D);
        assertEquals(0.5D, window.ratio(900L, 1_000L, 2_500L), 1.0E-9D);
        assertEquals(850D / 900D, window.ratio(900L, 1_000L, 3_000L), 1.0E-9D);
    }

    @Test
    void aCounterResetYieldsNoRatioInsteadOfANegativeOne() {
        RatioWindow window = new RatioWindow();

        window.ratio(5_000L, 10_000L, 1_000L);
        assertTrue(Double.isNaN(window.ratio(10L, 100L, 2_000L)));
    }

    @Test
    void clearRestartsThePriming() {
        RatioWindow window = new RatioWindow();

        window.ratio(0L, 0L, 1_000L);
        window.ratio(50L, 100L, 2_000L);
        window.clear();

        assertTrue(Double.isNaN(window.ratio(60L, 120L, 3_000L)));
    }
}
