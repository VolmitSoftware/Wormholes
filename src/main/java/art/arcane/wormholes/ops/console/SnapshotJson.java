package art.arcane.wormholes.ops.console;

import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONObject;

import java.util.List;
import java.util.Map;

/** The /snapshot document: current metrics, peers, failure counts, and the history windows. */
public final class SnapshotJson {
    private SnapshotJson() {
    }

    public static String render(MetricsSource source, MetricsHistory history, long nowMillis) {
        JSONObject root = new JSONObject();
        root.put("generatedAtMillis", nowMillis);

        JSONObject metrics = new JSONObject();
        for (Map.Entry<String, Double> metric : source.metrics().entrySet()) {
            metrics.put(metric.getKey(), metric.getValue().doubleValue());
        }
        root.put("metrics", metrics);

        JSONArray peers = new JSONArray();
        for (MetricsSource.Peer peer : source.peers()) {
            JSONObject entry = new JSONObject();
            entry.put("peer", peer.name());
            entry.put("transport", peer.transport());
            entry.put("compression", peer.compression());
            entry.put("rttMillis", peer.rttMillis());
            entry.put("connected", peer.connected());
            peers.put(entry);
        }
        root.put("peers", peers);

        JSONObject failures = new JSONObject();
        for (Map.Entry<String, Long> failure : source.failures().entrySet()) {
            failures.put(failure.getKey(), failure.getValue().longValue());
        }
        root.put("failures", failures);

        JSONObject windows = new JSONObject();
        for (String key : history.keys()) {
            List<MetricsHistory.Point> series = history.series(key);
            JSONArray points = new JSONArray();
            for (MetricsHistory.Point point : series) {
                JSONArray pair = new JSONArray();
                pair.put(point.atMillis());
                pair.put(point.value());
                points.put(pair);
            }
            windows.put(key, points);
        }
        root.put("history", windows);

        return root.toString();
    }
}
