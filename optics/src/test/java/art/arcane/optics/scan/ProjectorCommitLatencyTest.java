package art.arcane.optics.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class ProjectorCommitLatencyTest {
    private static final long TICK_NANOS = 50_000_000L;

    @Test
    void reportsBeginToCommitPercentilesInTicks() {
        ProjectorCommitLatency latency = new ProjectorCommitLatency();
        for (int ticks = 1; ticks <= 20; ticks++) {
            latency.begin(1_000L);
            latency.commit(1_000L + ticks * TICK_NANOS);
        }

        assertEquals(20, latency.sampleCount());
        assertEquals(10.0D, latency.percentileTicks(0.5D), 1.0E-9D);
        assertEquals(19.0D, latency.percentileTicks(0.95D), 1.0E-9D);
        assertEquals("10.0/19.0", latency.describe());
    }

    @Test
    void cancelledAndUnstartedCommitsAreNotSampled() {
        ProjectorCommitLatency latency = new ProjectorCommitLatency();
        latency.commit(TICK_NANOS);
        latency.begin(0L);
        latency.cancel();
        latency.commit(TICK_NANOS);

        assertEquals(0, latency.sampleCount());
        assertEquals("0.0/0.0", latency.describe());
    }

    @Test
    void keepsOnlyTheMostRecentWindow() {
        ProjectorCommitLatency latency = new ProjectorCommitLatency();
        for (int index = 0; index < 40; index++) {
            latency.begin(0L);
            latency.commit(index < 8 ? 100L * TICK_NANOS : TICK_NANOS);
        }

        assertEquals(32, latency.sampleCount());
        assertEquals(1.0D, latency.percentileTicks(0.95D), 1.0E-9D);
    }
}
