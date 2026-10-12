package art.arcane.automator;

public final class FrameTimingsTest {
    public static void main(String[] arguments) {
        resetOmitsFirstInterval();
        ringRetainsLatestIntervals();
        thresholdsAreStrict();
        quantilesUseNearestRank();
        timestampsHandleWrapAndRegression();
        capacityMustBePositive();
        System.out.println("Frame interval reset, bounded ring, strict thresholds and nearest-rank quantiles passed");
    }

    private static void resetOmitsFirstInterval() {
        FrameTimings timings = new FrameTimings(4);
        FrameTimings.Snapshot empty = timings.snapshot();
        require(empty.samples() == 0 && empty.totalSamples() == 0L && empty.maxNanos() == 0L
                && empty.p50Nanos() == 0L && empty.p95Nanos() == 0L && empty.p99Nanos() == 0L,
                "Empty metrics must have no samples or quantiles");
        timings.record(0L);
        require(timings.snapshot().samples() == 0, "The first frame must establish the baseline even at zero");
        timings.record(300_000_000L);
        require(timings.snapshot().over250Ms() == 1, "Initial interval must be recorded");
        timings.reset();
        require(timings.snapshot().samples() == 0 && timings.snapshot().totalSamples() == 0L
                && timings.snapshot().over250Ms() == 0, "Reset must discard samples and counters");
        timings.record(9_000_000_000L);
        require(timings.snapshot().samples() == 0, "The first frame after reset must omit the old gap");
        timings.record(9_016_000_000L);
        FrameTimings.Snapshot restarted = timings.snapshot();
        require(restarted.samples() == 1 && restarted.totalSamples() == 1L && restarted.maxNanos() == 16_000_000L
                && restarted.p50Nanos() == restarted.maxNanos() && restarted.p99Nanos() == restarted.maxNanos(),
                "Only the new interval must contribute after reset");
    }

    private static void ringRetainsLatestIntervals() {
        FrameTimings timings = new FrameTimings(3);
        long timestamp = 0L;
        timings.record(timestamp);
        for (long interval : new long[]{300_000_000L, 200_000_000L, 100_000_000L, 30_000_000L, 20_000_000L, 10_000_000L}) {
            timestamp += interval;
            timings.record(timestamp);
        }
        FrameTimings.Snapshot retained = timings.snapshot();
        require(retained.samples() == 3 && retained.capacity() == 3 && retained.totalSamples() == 6L,
                "The ring must bound retained samples while counting all intervals since reset");
        require(retained.p50Nanos() == 20_000_000L && retained.maxNanos() == 30_000_000L
                && retained.over50Ms() == 0 && retained.over100Ms() == 0 && retained.over250Ms() == 0,
                "Evicted intervals must not affect quantiles, maximum or thresholds");
        timings.record(timestamp + 40_000_000L);
        require(timings.snapshot().p50Nanos() == 20_000_000L && timings.snapshot().maxNanos() == 40_000_000L,
                "Snapshot sorting must not change which ring entry is evicted next");
        require(retained.maxNanos() == 30_000_000L, "A snapshot must remain unchanged after recording");
        FrameTimings single = new FrameTimings(1);
        single.record(0L);
        single.record(10L);
        single.record(30L);
        require(single.snapshot().samples() == 1 && single.snapshot().p95Nanos() == 20L,
                "A one-slot ring must retain only the latest interval");
    }

    private static void thresholdsAreStrict() {
        FrameTimings timings = new FrameTimings(8);
        long timestamp = 0L;
        timings.record(timestamp);
        for (long interval : new long[]{50_000_000L, 50_000_001L, 100_000_000L, 100_000_001L, 250_000_000L, 250_000_001L}) {
            timestamp += interval;
            timings.record(timestamp);
        }
        FrameTimings.Snapshot result = timings.snapshot();
        require(result.over50Ms() == 5 && result.over100Ms() == 3 && result.over250Ms() == 1,
                "Threshold counts must mean strictly over 50, 100 and 250 milliseconds");
    }

    private static void quantilesUseNearestRank() {
        FrameTimings timings = new FrameTimings(100);
        long timestamp = 0L;
        timings.record(timestamp);
        for (int milliseconds = 100; milliseconds >= 1; milliseconds--) {
            timestamp += milliseconds * 1_000_000L;
            timings.record(timestamp);
        }
        FrameTimings.Snapshot result = timings.snapshot();
        require(result.p50Nanos() == 50_000_000L && result.p95Nanos() == 95_000_000L
                && result.p99Nanos() == 99_000_000L && result.maxNanos() == 100_000_000L,
                "Quantiles must sort intervals and use nearest ranks");
        timings.reset();
        timestamp = 0L;
        timings.record(timestamp);
        for (int interval : new int[]{7, 2, 4}) {
            timestamp += interval;
            timings.record(timestamp);
        }
        require(timings.snapshot().p50Nanos() == 4L && timings.snapshot().p95Nanos() == 7L
                && timings.snapshot().p99Nanos() == 7L, "Fractional ranks must round up");
    }

    private static void timestampsHandleWrapAndRegression() {
        FrameTimings timings = new FrameTimings(4);
        timings.record(Long.MAX_VALUE - 5L);
        timings.record(Long.MIN_VALUE + 4L);
        require(timings.snapshot().maxNanos() == 10L, "Monotonic timestamp wrap must preserve elapsed time");
        timings.reset();
        timings.record(20L);
        timings.record(10L);
        require(timings.snapshot().samples() == 0, "Negative elapsed time must not become a sample");
        timings.record(15L);
        require(timings.snapshot().maxNanos() == 5L, "An invalid timestamp must rebase the next interval");
    }

    private static void capacityMustBePositive() {
        for (int capacity : new int[]{0, -1}) {
            try {
                new FrameTimings(capacity);
                throw new AssertionError("Nonpositive ring capacity must fail");
            } catch (IllegalArgumentException expected) {
                require(expected.getMessage().contains("positive"), "Capacity failure must explain the constraint");
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
