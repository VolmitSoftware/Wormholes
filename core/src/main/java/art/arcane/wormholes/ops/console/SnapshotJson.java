package art.arcane.wormholes.ops.console;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/** The /snapshot document: current metrics, peers, failure counts, and the history windows. */
public final class SnapshotJson {
    private SnapshotJson() {
    }

    public static String render(MetricsSource source, MetricsHistory history, long nowMillis) {
        JsonObject root = new JsonObject();
        root.addProperty("generatedAtMillis", nowMillis);

        JsonObject metrics = new JsonObject();
        for (Map.Entry<String, Double> metric : source.metrics().entrySet()) {
            metrics.addProperty(metric.getKey(), metric.getValue().doubleValue());
        }
        root.add("metrics", metrics);

        JsonArray peers = new JsonArray();
        for (MetricsSource.Peer peer : source.peers()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("peer", peer.name());
            entry.addProperty("transport", peer.transport());
            entry.addProperty("compression", peer.compression());
            entry.addProperty("rttMillis", peer.rttMillis());
            entry.addProperty("connected", peer.connected());
            peers.add(entry);
        }
        root.add("peers", peers);

        JsonObject failures = new JsonObject();
        for (Map.Entry<String, Long> failure : source.failures().entrySet()) {
            failures.addProperty(failure.getKey(), failure.getValue().longValue());
        }
        root.add("failures", failures);

        JsonObject windows = new JsonObject();
        for (String key : history.keys()) {
            List<MetricsHistory.Point> series = history.series(key);
            JsonArray points = new JsonArray();
            for (MetricsHistory.Point point : series) {
                JsonArray pair = new JsonArray();
                pair.add(point.atMillis());
                pair.add(point.value());
                points.add(pair);
            }
            windows.add(key, points);
        }
        root.add("history", windows);

        return root.toString();
    }
}
