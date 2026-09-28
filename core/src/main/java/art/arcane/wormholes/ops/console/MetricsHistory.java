package art.arcane.wormholes.ops.console;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** One 1 Hz ring buffer per metric key, sized by {@code [ops.console] history-minutes}. */
public final class MetricsHistory {
    public static final int MIN_MINUTES = 1;
    public static final int MAX_MINUTES = 1_440;
    private static final long SAMPLE_INTERVAL_MILLIS = 1_000L;

    /** One sampled value and the millisecond it was taken. */
    public record Point(long atMillis, double value) {
    }

    private final int capacity;
    private final ConcurrentMap<String, Ring> rings = new ConcurrentHashMap<>();
    private volatile long lastSampleMillis;

    public MetricsHistory(int historyMinutes) {
        this.capacity = Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, historyMinutes)) * 60;
    }

    public int capacity() {
        return capacity;
    }

    /** Records a sample, ignoring anything that arrives faster than 1 Hz. */
    public void record(Map<String, Double> metrics, long nowMillis) {
        if (metrics == null || metrics.isEmpty()) {
            return;
        }
        long last = lastSampleMillis;
        if (last != 0L && nowMillis - last < SAMPLE_INTERVAL_MILLIS) {
            return;
        }
        lastSampleMillis = nowMillis;
        for (Map.Entry<String, Double> metric : metrics.entrySet()) {
            if (metric.getKey() == null || metric.getValue() == null) {
                continue;
            }
            rings.computeIfAbsent(metric.getKey(), key -> new Ring(capacity))
                .add(nowMillis, metric.getValue().doubleValue());
        }
    }

    public Set<String> keys() {
        return Collections.unmodifiableSet(rings.keySet());
    }

    /** Oldest to newest. */
    public List<Point> series(String key) {
        Ring ring = key == null ? null : rings.get(key);
        return ring == null ? List.of() : ring.points();
    }

    public void clear() {
        rings.clear();
        lastSampleMillis = 0L;
    }

    private static final class Ring {
        private final Point[] points;
        private int next;
        private int size;

        private Ring(int capacity) {
            this.points = new Point[capacity];
        }

        private synchronized void add(long atMillis, double value) {
            points[next] = new Point(atMillis, value);
            next = (next + 1) % points.length;
            if (size < points.length) {
                size++;
            }
        }

        private synchronized List<Point> points() {
            List<Point> out = new ArrayList<>(size);
            for (int step = size; step > 0; step--) {
                out.add(points[Math.floorMod(next - step, points.length)]);
            }
            return Collections.unmodifiableList(out);
        }
    }
}
