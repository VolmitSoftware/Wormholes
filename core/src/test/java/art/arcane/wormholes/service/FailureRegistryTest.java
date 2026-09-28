package art.arcane.wormholes.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureRegistryTest {
    @BeforeEach
    void reset() {
        FailureRegistry.clear();
    }

    @AfterEach
    void clear() {
        FailureRegistry.clear();
    }

    @Test
    void recordingCountsPerIdAndKeepsTheLastSeenTimestamp() {
        FailureRegistry.record("HANDOFF_TIMED_OUT", "peer=beta", 1_000L);
        FailureRegistry.record("HANDOFF_TIMED_OUT", "peer=gamma", 2_500L);
        FailureRegistry.record("STATS_SNAPSHOT_SCHEDULER_UNAVAILABLE", null, 2_000L);

        Map<String, Long> counts = FailureRegistry.counts();
        assertEquals(2L, counts.get("HANDOFF_TIMED_OUT"));
        assertEquals(1L, counts.get("STATS_SNAPSHOT_SCHEDULER_UNAVAILABLE"));
        assertEquals(2_500L, FailureRegistry.lastSeenMillis("HANDOFF_TIMED_OUT"));
        assertEquals(2_000L, FailureRegistry.lastSeenMillis("STATS_SNAPSHOT_SCHEDULER_UNAVAILABLE"));
        assertEquals(0L, FailureRegistry.lastSeenMillis("NEVER_SEEN"));
    }

    @Test
    void recentReturnsNewestFirstAndDropsEntriesOutsideTheWindow() {
        FailureRegistry.record("OLD", "gone", 1_000L);
        FailureRegistry.record("NEW", "kept", 90_000L);
        FailureRegistry.record("NEWEST", "kept", 95_000L);

        List<FailureRegistry.Entry> recent = FailureRegistry.recent(60_000L, 100_000L);

        assertEquals(2, recent.size());
        assertEquals("NEWEST", recent.get(0).id());
        assertEquals("NEW", recent.get(1).id());
        assertEquals("kept", recent.get(0).detail());
    }

    @Test
    void recentLinesRenderIdAndDetailAndRespectTheLimit() {
        for (int index = 0; index < 12; index++) {
            FailureRegistry.record("FAILURE_" + index, index % 2 == 0 ? "detail=" + index : null, 1_000L + index);
        }

        List<String> lines = FailureRegistry.recentLines(60_000L, 1_012L, 10);

        assertEquals(10, lines.size());
        assertTrue(lines.get(0).contains("FAILURE_11"), lines.get(0));
        assertTrue(lines.get(1).contains("FAILURE_10 detail=10"), lines.get(1));
        assertTrue(lines.get(0).startsWith("1970-01-01T00:00:01"), lines.get(0));
    }

    @Test
    void theRingKeepsOnlyTheMostRecentEntries() {
        int overflow = FailureRegistry.RING_CAPACITY + 20;
        for (int index = 0; index < overflow; index++) {
            FailureRegistry.record("FAILURE", "index=" + index, 1_000L + index);
        }

        List<FailureRegistry.Entry> recent = FailureRegistry.recent(600_000L, 1_000L + overflow);

        assertEquals(FailureRegistry.RING_CAPACITY, recent.size());
        assertEquals("index=" + (overflow - 1), recent.get(0).detail());
        assertEquals(overflow, FailureRegistry.counts().get("FAILURE"));
    }

    @Test
    void telemetryFailuresLandInTheRegistry() {
        WormholesTelemetry.clear();
        WormholesTelemetry.countFailure("PROJECTION_FLUSH_FAILED");

        assertEquals(1L, FailureRegistry.counts().get("PROJECTION_FLUSH_FAILED"));
        assertEquals(Map.of("PROJECTION_FLUSH_FAILED", 1L), WormholesTelemetry.failureBreakdown());
        WormholesTelemetry.clear();
        assertTrue(FailureRegistry.counts().isEmpty());
    }
}
