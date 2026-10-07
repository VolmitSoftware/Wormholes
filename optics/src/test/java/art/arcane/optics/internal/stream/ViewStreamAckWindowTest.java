package art.arcane.optics.internal.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ViewStreamAckWindowTest {
    @Test
    void aCumulativeAckFreesEveryClosedGroupUpToItsSequence() {
        ViewStreamAckWindow window = new ViewStreamAckWindow(4);
        window.record(1, 100L);
        window.record(2, 200L);
        window.record(3, 300L);
        assertTrue(window.ack(2, 5, 1_000L));
        assertEquals(1, window.outstanding());
        assertEquals(2L, window.acked());
        assertEquals(800L, window.lastRttNanos());
        assertEquals(5L, window.appliedCells());
        assertFalse(window.ack(2, 0, 1_100L));
        assertTrue(window.ack(3, 0, 1_200L));
        assertEquals(0, window.outstanding());
    }

    @Test
    void anOpenManifestIsNeverFreedByACumulativeAck() {
        ViewStreamAckWindow window = new ViewStreamAckWindow(2);
        window.open(5, 100L);
        window.record(6, 200L);
        assertTrue(window.full());
        assertTrue(window.ack(6, 0, 300L));
        assertEquals(1, window.outstanding());
        assertFalse(window.full());
        assertFalse(window.ack(1_000, 0, 400L), "an ack far past the manifest sequence must not free the unsent bricks");
        assertEquals(1, window.outstanding());
    }

    @Test
    void freeingIsNotBlockedByAnEarlierOpenManifest() {
        ViewStreamAckWindow window = new ViewStreamAckWindow(3);
        window.open(5, 100L);
        window.record(6, 200L);
        window.record(7, 300L);
        assertTrue(window.full());
        assertTrue(window.ack(7, 0, 900L));
        assertEquals(1, window.outstanding());
        assertEquals(2L, window.acked());
    }

    @Test
    void closingAManifestMovesItToTheSequenceThatEndsTheStream() {
        ViewStreamAckWindow window = new ViewStreamAckWindow(4);
        window.open(5, 100L);
        window.record(6, 200L);
        assertTrue(window.close(5, 9, 500L));
        assertFalse(window.close(5, 9, 500L), "a manifest closes once");
        assertTrue(window.ack(8, 0, 700L));
        assertEquals(1, window.outstanding());
        assertTrue(window.ack(9, 0, 1_500L));
        assertEquals(0, window.outstanding());
        assertEquals(1_000L, window.lastRttNanos(), "the manifest round trip is measured from the frame that ends the stream");
    }

    @Test
    void abandoningAManifestReleasesItsSlotWithoutAnAck() {
        ViewStreamAckWindow window = new ViewStreamAckWindow(1);
        window.open(5, 100L);
        assertTrue(window.full());
        assertFalse(window.abandon(6));
        assertTrue(window.abandon(5));
        assertFalse(window.full());
        assertEquals(0, window.outstanding());
        assertEquals(0L, window.acked());
    }

    @Test
    void aZeroCapacityWindowTracksWithoutEverFilling() {
        ViewStreamAckWindow window = new ViewStreamAckWindow(0);
        for (int sequence = 0; sequence < 200; sequence++) {
            window.record(sequence, sequence);
            assertFalse(window.full());
        }
        assertTrue(window.outstanding() <= 64);
        assertTrue(window.ack(199, 0, 1_000L));
        assertEquals(0, window.outstanding());
    }

    @Test
    void clearingForgetsOutstandingGroupsButKeepsTheTotals() {
        ViewStreamAckWindow window = new ViewStreamAckWindow(4);
        window.record(1, 0L);
        window.open(2, 0L);
        window.ack(1, 3, 10L);
        window.clear();
        assertEquals(0, window.outstanding());
        assertEquals(1L, window.acked());
        assertEquals(3L, window.appliedCells());
        assertFalse(window.close(2, 3, 20L));
    }
}
