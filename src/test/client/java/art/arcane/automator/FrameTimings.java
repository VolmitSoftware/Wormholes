package art.arcane.automator;

import java.util.Arrays;

final class FrameTimings {
    private final long[] intervals;
    private int next;
    private int samples;
    private long totalSamples;
    private long previousFrame;
    private boolean hasPreviousFrame;

    FrameTimings(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Frame interval capacity must be positive");
        }
        intervals = new long[capacity];
    }

    void record(long frameNanos) {
        if (hasPreviousFrame) {
            long interval = frameNanos - previousFrame;
            if (interval >= 0L) {
                intervals[next] = interval;
                next = (next + 1) % intervals.length;
                samples = Math.min(samples + 1, intervals.length);
                totalSamples++;
            }
        }
        previousFrame = frameNanos;
        hasPreviousFrame = true;
    }

    void reset() {
        next = 0;
        samples = 0;
        totalSamples = 0L;
        hasPreviousFrame = false;
    }

    Snapshot snapshot() {
        long[] sorted = Arrays.copyOf(intervals, samples);
        Arrays.sort(sorted);
        int over50Ms = 0;
        int over100Ms = 0;
        int over250Ms = 0;
        for (long interval : sorted) {
            if (interval > 50_000_000L) {
                over50Ms++;
            }
            if (interval > 100_000_000L) {
                over100Ms++;
            }
            if (interval > 250_000_000L) {
                over250Ms++;
            }
        }
        return new Snapshot(samples, intervals.length, totalSamples,
                percentile(sorted, 50), percentile(sorted, 95), percentile(sorted, 99),
                samples == 0 ? 0L : sorted[samples - 1], over50Ms, over100Ms, over250Ms);
    }

    private static long percentile(long[] sorted, int percent) {
        if (sorted.length == 0) {
            return 0L;
        }
        int rank = (int) ((sorted.length * (long) percent + 99L) / 100L);
        return sorted[rank - 1];
    }

    record Snapshot(int samples, int capacity, long totalSamples, long p50Nanos, long p95Nanos,
                    long p99Nanos, long maxNanos, int over50Ms, int over100Ms, int over250Ms) {
    }
}
