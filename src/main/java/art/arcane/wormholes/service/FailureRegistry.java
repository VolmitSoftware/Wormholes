package art.arcane.wormholes.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One vocabulary for every operator-visible failure. Traversal ledger names, door transit reasons
 * and plugin failure strings all land here through {@link WormholesTelemetry#countFailure(String)},
 * which gives the stats snapshot and the metrics endpoint one place to read counts, last-seen
 * timestamps and the recent ring from.
 */
public final class FailureRegistry {
    static final int RING_CAPACITY = 256;

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);
    private static final ConcurrentMap<String, AtomicLong> COUNTS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, AtomicLong> LAST_SEEN = new ConcurrentHashMap<>();
    private static final Object RING_LOCK = new Object();
    private static final Entry[] RING = new Entry[RING_CAPACITY];
    private static int ringNext;
    private static int ringSize;

    /** One recorded failure: its id, an optional detail, and when it happened. */
    public record Entry(String id, String detail, long atMillis) {
    }

    private FailureRegistry() {
    }

    public static void record(String id) {
        record(id, null, System.currentTimeMillis());
    }

    public static void record(String id, String detail) {
        record(id, detail, System.currentTimeMillis());
    }

    public static void record(String id, String detail, long nowMillis) {
        if (id == null || id.isEmpty()) {
            return;
        }
        COUNTS.computeIfAbsent(id, key -> new AtomicLong()).incrementAndGet();
        LAST_SEEN.computeIfAbsent(id, key -> new AtomicLong()).set(nowMillis);
        Entry entry = new Entry(id, detail == null || detail.isBlank() ? null : detail, nowMillis);
        synchronized (RING_LOCK) {
            RING[ringNext] = entry;
            ringNext = (ringNext + 1) % RING_CAPACITY;
            if (ringSize < RING_CAPACITY) {
                ringSize++;
            }
        }
    }

    public static Map<String, Long> counts() {
        Map<String, Long> counts = new TreeMap<>();
        COUNTS.forEach((id, counter) -> counts.put(id, Long.valueOf(counter.get())));
        return Collections.unmodifiableMap(counts);
    }

    public static long lastSeenMillis(String id) {
        AtomicLong seen = id == null ? null : LAST_SEEN.get(id);
        return seen == null ? 0L : seen.get();
    }

    public static List<Entry> recent(long windowMillis) {
        return recent(windowMillis, System.currentTimeMillis());
    }

    /** Entries recorded within the window, newest first. */
    public static List<Entry> recent(long windowMillis, long nowMillis) {
        long oldest = nowMillis - Math.max(0L, windowMillis);
        List<Entry> recent = new ArrayList<>();
        synchronized (RING_LOCK) {
            for (int step = 1; step <= ringSize; step++) {
                Entry entry = RING[Math.floorMod(ringNext - step, RING_CAPACITY)];
                if (entry == null || entry.atMillis() < oldest) {
                    break;
                }
                recent.add(entry);
            }
        }
        return Collections.unmodifiableList(recent);
    }

    public static List<String> recentLines(long windowMillis, int limit) {
        return recentLines(windowMillis, System.currentTimeMillis(), limit);
    }

    public static List<String> recentLines(long windowMillis, long nowMillis, int limit) {
        List<Entry> recent = recent(windowMillis, nowMillis);
        List<String> lines = new ArrayList<>(Math.min(recent.size(), Math.max(0, limit)));
        for (Entry entry : recent) {
            if (lines.size() >= limit) {
                break;
            }
            lines.add(TIMESTAMP.format(Instant.ofEpochMilli(entry.atMillis())) + " " + entry.id()
                + (entry.detail() == null ? "" : " " + entry.detail()));
        }
        return Collections.unmodifiableList(lines);
    }

    public static void clear() {
        COUNTS.clear();
        LAST_SEEN.clear();
        synchronized (RING_LOCK) {
            Arrays.fill(RING, null);
            ringNext = 0;
            ringSize = 0;
        }
    }
}
