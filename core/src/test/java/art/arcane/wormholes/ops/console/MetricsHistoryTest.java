package art.arcane.wormholes.ops.console;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsHistoryTest {
    @Test
    void samplesFasterThanOneHertzAreIgnored() {
        MetricsHistory history = new MetricsHistory(1);

        history.record(Map.of("wormholes.portals", Double.valueOf(1.0D)), 1_000L);
        history.record(Map.of("wormholes.portals", Double.valueOf(2.0D)), 1_500L);
        history.record(Map.of("wormholes.portals", Double.valueOf(3.0D)), 2_000L);

        List<MetricsHistory.Point> series = history.series("wormholes.portals");
        assertEquals(2, series.size());
        assertEquals(1.0D, series.get(0).value());
        assertEquals(3.0D, series.get(1).value());
    }

    @Test
    void theRingHoldsOneSamplePerSecondForTheConfiguredMinutes() {
        MetricsHistory history = new MetricsHistory(1);
        assertEquals(60, history.capacity());

        for (int second = 0; second < 90; second++) {
            history.record(Map.of("wormholes.portals", Double.valueOf(second)), second * 1_000L);
        }

        List<MetricsHistory.Point> series = history.series("wormholes.portals");
        assertEquals(60, series.size());
        assertEquals(30.0D, series.get(0).value());
        assertEquals(89.0D, series.get(59).value());
        assertEquals(89_000L, series.get(59).atMillis());
    }

    @Test
    void everyRecordedKeyGetsItsOwnSeriesAndUnknownKeysAreEmpty() {
        MetricsHistory history = new MetricsHistory(5);
        history.record(Map.of("a", Double.valueOf(1.0D), "b", Double.valueOf(2.0D)), 1_000L);
        history.record(Map.of("a", Double.valueOf(3.0D)), 2_000L);

        assertEquals(Set.copyOf(history.keys()), Set.of("a", "b"));
        assertEquals(2, history.series("a").size());
        assertEquals(1, history.series("b").size());
        assertTrue(history.series("missing").isEmpty());
    }

    @Test
    void historyMinutesAreClampedToTheConfiguredBounds() {
        assertEquals(60, new MetricsHistory(0).capacity());
        assertEquals(1_440 * 60, new MetricsHistory(100_000).capacity());
    }
}
