package art.arcane.wormholes.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RollingMinuteCounterTest {
    @Test
    void oneEventReadsAsOnePerMinuteForTheWholeMinuteAfterIt() {
        RollingMinuteCounter counter = new RollingMinuteCounter();

        counter.add(100_250L, 1L);

        assertEquals(1L, counter.sum(100_300L));
        assertEquals(1L, counter.sum(130_000L));
        assertEquals(1L, counter.sum(159_999L));
        assertEquals(0L, counter.sum(160_000L));
    }

    @Test
    void eventsAcrossSecondsSumOverTheTrailingMinute() {
        RollingMinuteCounter counter = new RollingMinuteCounter();

        counter.add(10_000L, 2L);
        counter.add(40_500L, 3L);
        counter.add(69_900L, 4L);

        assertEquals(9L, counter.sum(69_999L));
        assertEquals(7L, counter.sum(70_000L));
        assertEquals(4L, counter.sum(100_499L));
        assertEquals(4L, counter.sum(128_999L));
        assertEquals(0L, counter.sum(129_000L));
    }

    @Test
    void aReusedSlotDropsTheCountFromTheMinuteBefore() {
        RollingMinuteCounter counter = new RollingMinuteCounter();

        counter.add(5_000L, 7L);
        counter.add(65_000L, 1L);

        assertEquals(1L, counter.sum(65_000L));
    }

    @Test
    void readingBeforeTheRecordedSecondIgnoresFutureSlots() {
        RollingMinuteCounter counter = new RollingMinuteCounter();

        counter.add(50_000L, 5L);

        assertEquals(0L, counter.sum(49_999L));
    }

    @Test
    void clearForgetsEverySlot() {
        RollingMinuteCounter counter = new RollingMinuteCounter();

        counter.add(1_000L, 3L);
        counter.clear();

        assertEquals(0L, counter.sum(1_000L));
    }
}
