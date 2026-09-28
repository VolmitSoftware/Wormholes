package art.arcane.wormholes.ops.console;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenMetricsRendererTest {
    @Test
    void metricKeysBecomeUnderscoredSeriesWithATypeLine() {
        String rendered = OpenMetricsRenderer.render(new RecordedMetrics(
            Map.of("wormholes.portals", Double.valueOf(12.0D)), List.of(), Map.of()));

        assertTrue(rendered.contains("# TYPE wormholes_portals gauge\n"), rendered);
        assertTrue(rendered.contains("wormholes_portals 12.0\n"), rendered);
        assertTrue(rendered.endsWith("# EOF\n"), rendered);
    }

    @Test
    void peersRenderWithPeerTransportAndCompressionLabels() {
        String rendered = OpenMetricsRenderer.render(new RecordedMetrics(
            Map.of(),
            List.of(new MetricsSource.Peer("beta", "TCP", "dict", 12L, true),
                new MetricsSource.Peer("gamma", "SIDEBAND", "none", 40L, false)),
            Map.of()));

        assertTrue(rendered.contains("# TYPE wormholes_peer_rtt_milliseconds gauge\n"), rendered);
        assertTrue(rendered.contains(
            "wormholes_peer_rtt_milliseconds{peer=\"beta\",transport=\"TCP\",compression=\"dict\"} 12\n"), rendered);
        assertTrue(rendered.contains(
            "wormholes_peer_connected{peer=\"gamma\",transport=\"SIDEBAND\",compression=\"none\"} 0\n"), rendered);
    }

    @Test
    void failureCountersRenderPerReason() {
        String rendered = OpenMetricsRenderer.render(new RecordedMetrics(
            Map.of(), List.of(), Map.of("HANDOFF_TIMED_OUT", Long.valueOf(3L))));

        assertTrue(rendered.contains("# TYPE wormholes_failures_total counter\n"), rendered);
        assertTrue(rendered.contains("wormholes_failures_total{reason=\"HANDOFF_TIMED_OUT\"} 3\n"), rendered);
    }

    @Test
    void labelValuesAreEscapedAndTheSeriesCountIsCapped() {
        Map<String, Long> failures = new LinkedHashMap<>();
        for (int index = 0; index < OpenMetricsRenderer.MAX_SERIES + 50; index++) {
            failures.put(String.format("REASON_%03d", Integer.valueOf(index)), Long.valueOf(index));
        }
        failures.put("QUOTED\"NAME\\", Long.valueOf(1L));

        String rendered = OpenMetricsRenderer.render(new RecordedMetrics(Map.of(), List.of(), failures));

        long series = rendered.lines().filter(line -> !line.startsWith("#") && !line.isBlank()).count();
        assertEquals(OpenMetricsRenderer.MAX_SERIES, series);
        assertTrue(rendered.contains("REASON_254"), rendered);
        assertFalse(rendered.contains("REASON_255"), rendered);

        String escaped = OpenMetricsRenderer.render(new RecordedMetrics(
            Map.of(), List.of(), Map.of("QUOTED\"NAME\\", Long.valueOf(1L))));
        assertTrue(escaped.contains("reason=\"QUOTED\\\"NAME\\\\\""), escaped);
    }

    private record RecordedMetrics(Map<String, Double> metrics, List<MetricsSource.Peer> peers,
                                   Map<String, Long> failures) implements MetricsSource {
    }
}
