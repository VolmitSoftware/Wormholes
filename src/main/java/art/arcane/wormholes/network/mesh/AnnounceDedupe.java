package art.arcane.wormholes.network.mesh;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * Drops repeated announce copies and throttles chatty sources. A (name, epoch) pair is admitted once
 * per window; each forwarding source may admit at most {@code perSourcePerMinute} announces per
 * rolling minute. Not thread-safe by itself; callers hold their own lock (handlers run on peer
 * reader threads).
 */
public final class AnnounceDedupe {
    private static final long MINUTE_MILLIS = 60_000L;

    private final long windowMillis;
    private final IntSupplier perSourcePerMinute;
    private final Map<String, Long> seen = new HashMap<>();
    private final Map<String, SourceWindow> sources = new HashMap<>();

    public AnnounceDedupe(long windowMillis, int perSourcePerMinute) {
        this(windowMillis, () -> perSourcePerMinute);
    }

    /** The per-source limit is read on every call so a config reload applies without rebuilding the window. */
    public AnnounceDedupe(long windowMillis, IntSupplier perSourcePerMinute) {
        this.windowMillis = Math.max(1L, windowMillis);
        this.perSourcePerMinute = perSourcePerMinute;
    }

    public synchronized boolean admit(String name, long epoch, String source, long nowMillis) {
        prune(nowMillis);
        SourceWindow window = sources.computeIfAbsent(source, ignored -> new SourceWindow(nowMillis));
        if (nowMillis - window.startMillis >= MINUTE_MILLIS) {
            window.startMillis = nowMillis;
            window.count = 0;
        }
        if (window.count >= Math.max(1, perSourcePerMinute.getAsInt())) {
            return false;
        }
        String key = name + '@' + epoch;
        Long previous = seen.get(key);
        if (previous != null && nowMillis - previous.longValue() < windowMillis) {
            return false;
        }
        seen.put(key, Long.valueOf(nowMillis));
        window.count++;
        return true;
    }

    public synchronized int trackedCount() {
        return seen.size();
    }

    private void prune(long nowMillis) {
        Iterator<Map.Entry<String, Long>> entries = seen.entrySet().iterator();
        while (entries.hasNext()) {
            if (nowMillis - entries.next().getValue().longValue() >= windowMillis) {
                entries.remove();
            }
        }
        Iterator<Map.Entry<String, SourceWindow>> windows = sources.entrySet().iterator();
        while (windows.hasNext()) {
            if (nowMillis - windows.next().getValue().startMillis >= 2L * MINUTE_MILLIS) {
                windows.remove();
            }
        }
    }

    private static final class SourceWindow {
        private long startMillis;
        private int count;

        private SourceWindow(long startMillis) {
            this.startMillis = startMillis;
        }
    }
}
