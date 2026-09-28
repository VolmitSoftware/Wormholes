package art.arcane.wormholes.network.mesh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnounceDedupeTest {
    @Test
    void sameNameAndEpochIsDroppedInsideTheWindowAndAdmittedAfterIt() {
        AnnounceDedupe dedupe = new AnnounceDedupe(30_000L, 30);
        assertTrue(dedupe.admit("gamma", 4L, "beta", 1_000L));
        assertFalse(dedupe.admit("gamma", 4L, "alpha", 2_000L));
        assertTrue(dedupe.admit("gamma", 5L, "alpha", 2_000L));
        assertTrue(dedupe.admit("gamma", 4L, "beta", 31_001L));
    }

    @Test
    void perSourceRateLimitCapsAnnouncesPerMinute() {
        AnnounceDedupe dedupe = new AnnounceDedupe(30_000L, 3);
        assertTrue(dedupe.admit("a", 1L, "beta", 0L));
        assertTrue(dedupe.admit("b", 1L, "beta", 0L));
        assertTrue(dedupe.admit("c", 1L, "beta", 0L));
        assertFalse(dedupe.admit("d", 1L, "beta", 0L));
        assertTrue(dedupe.admit("d", 1L, "alpha", 0L));
        assertTrue(dedupe.admit("e", 1L, "beta", 60_000L));
    }

    @Test
    void expiredEntriesArePrunedSoTheWindowDoesNotGrowUnbounded() {
        AnnounceDedupe dedupe = new AnnounceDedupe(1_000L, 1_000);
        for (int index = 0; index < 500; index++) {
            assertTrue(dedupe.admit("peer" + index, 1L, "beta", index));
        }
        assertTrue(dedupe.admit("late", 1L, "beta", 5_000L));
        assertTrue(dedupe.trackedCount() <= 1);
    }
}
