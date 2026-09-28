package art.arcane.wormholes.ops.console;

import java.util.Map;
import java.util.TreeMap;

/** OpenMetrics text rendering with a hard series cap so one runaway label cannot flood a scrape. */
public final class OpenMetricsRenderer {
    public static final int MAX_SERIES = 256;

    private static final String PEER_RTT = "wormholes_peer_rtt_milliseconds";
    private static final String PEER_CONNECTED = "wormholes_peer_connected";
    private static final String FAILURES = "wormholes_failures_total";

    private OpenMetricsRenderer() {
    }

    public static String render(MetricsSource source) {
        StringBuilder out = new StringBuilder(4_096);
        int series = 0;

        for (Map.Entry<String, Double> metric : sorted(source.metrics()).entrySet()) {
            if (series >= MAX_SERIES) {
                return finish(out);
            }
            String name = seriesName(metric.getKey());
            out.append("# TYPE ").append(name).append(" gauge\n");
            out.append(name).append(' ').append(metric.getValue().doubleValue()).append('\n');
            series++;
        }

        boolean peerTypes = false;
        for (MetricsSource.Peer peer : source.peers()) {
            if (series + 2 > MAX_SERIES) {
                return finish(out);
            }
            if (!peerTypes) {
                out.append("# TYPE ").append(PEER_RTT).append(" gauge\n");
                out.append("# TYPE ").append(PEER_CONNECTED).append(" gauge\n");
                peerTypes = true;
            }
            String labels = "{peer=\"" + escape(peer.name()) + "\",transport=\"" + escape(peer.transport())
                + "\",compression=\"" + escape(peer.compression()) + "\"}";
            out.append(PEER_RTT).append(labels).append(' ').append(peer.rttMillis()).append('\n');
            out.append(PEER_CONNECTED).append(labels).append(' ').append(peer.connected() ? 1 : 0).append('\n');
            series += 2;
        }

        boolean failureType = false;
        for (Map.Entry<String, Long> failure : sorted(source.failures()).entrySet()) {
            if (series >= MAX_SERIES) {
                return finish(out);
            }
            if (!failureType) {
                out.append("# TYPE ").append(FAILURES).append(" counter\n");
                failureType = true;
            }
            out.append(FAILURES).append("{reason=\"").append(escape(failure.getKey())).append("\"} ")
                .append(failure.getValue().longValue()).append('\n');
            series++;
        }

        return finish(out);
    }

    static String seriesName(String metricKey) {
        StringBuilder name = new StringBuilder(metricKey.length());
        for (int index = 0; index < metricKey.length(); index++) {
            char character = metricKey.charAt(index);
            boolean valid = character == '_'
                || (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || (index > 0 && character >= '0' && character <= '9');
            name.append(valid ? character : '_');
        }
        return name.toString();
    }

    private static String escape(String label) {
        if (label == null) {
            return "";
        }
        return label.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static <T> Map<String, T> sorted(Map<String, T> values) {
        return values == null ? Map.of() : new TreeMap<>(values);
    }

    private static String finish(StringBuilder out) {
        return out.append("# EOF\n").toString();
    }
}
