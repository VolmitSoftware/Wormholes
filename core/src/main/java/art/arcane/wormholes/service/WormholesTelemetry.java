package art.arcane.wormholes.service;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class WormholesTelemetry {
    private static final long RATE_WINDOW_MS = 1000L;
    private static final AtomicLong BLOCK_CHANGES = new AtomicLong();
    private static final AtomicLong PACKETS = new AtomicLong();
    private static final AtomicLong RENDER_NANOS = new AtomicLong();
    private static final AtomicLong FAILURES = new AtomicLong();
    private static final RollingMinuteCounter TRAVERSALS_LAST_MINUTE = new RollingMinuteCounter();
    private static final RollingMinuteCounter FAILURES_LAST_MINUTE = new RollingMinuteCounter();
    private static final AtomicBoolean RATE_GATE = new AtomicBoolean();
    private static volatile int activeProjections;
    private static volatile int projectionObservers;
    private static volatile int spoofedEntities;
    private static volatile long windowStartMs;
    private static volatile long windowBlockChanges;
    private static volatile long windowPackets;
    private static volatile long windowRenderNanos;
    private static volatile double blockChangesPerSecond;
    private static volatile double packetsPerSecond;
    private static volatile double renderMsPerSecond;

    private WormholesTelemetry() {
    }

    public static void countBlockChange() {
        BLOCK_CHANGES.incrementAndGet();
    }

    public static void countPacket() {
        PACKETS.incrementAndGet();
    }

    public static void countTraversal() {
        TRAVERSALS_LAST_MINUTE.add(System.currentTimeMillis(), 1L);
    }

    public static void countFailure(String reason) {
        countFailure(reason, null);
    }

    public static void countFailure(String reason, String detail) {
        FAILURES.incrementAndGet();
        FAILURES_LAST_MINUTE.add(System.currentTimeMillis(), 1L);
        FailureRegistry.record(reason, detail);
    }

    public static long failures() {
        return FAILURES.get();
    }

    public static int failureReasonCount() {
        return FailureRegistry.counts().size();
    }

    public static Map<String, Long> failureBreakdown() {
        return FailureRegistry.counts();
    }

    public static void addRenderNanos(long nanos) {
        if (nanos > 0L) {
            RENDER_NANOS.addAndGet(nanos);
        }
    }

    public static void setProjectionGauges(int active, int observers, int spoofed) {
        activeProjections = active;
        projectionObservers = observers;
        spoofedEntities = spoofed;
    }

    public static int activeProjections() {
        return activeProjections;
    }

    public static int projectionObservers() {
        return projectionObservers;
    }

    public static int spoofedEntities() {
        return spoofedEntities;
    }

    public static double blockChangesPerSecond(long now) {
        refreshRates(now);
        return blockChangesPerSecond;
    }

    public static double packetsPerSecond(long now) {
        refreshRates(now);
        return packetsPerSecond;
    }

    public static double traversalsPerMinute(long now) {
        return TRAVERSALS_LAST_MINUTE.sum(now);
    }

    public static double renderMsPerSecond(long now) {
        refreshRates(now);
        return renderMsPerSecond;
    }

    public static double failuresPerMinute(long now) {
        return FAILURES_LAST_MINUTE.sum(now);
    }

    public static void clear() {
        while (!RATE_GATE.compareAndSet(false, true)) {
            Thread.onSpinWait();
        }

        try {
            BLOCK_CHANGES.set(0L);
            PACKETS.set(0L);
            RENDER_NANOS.set(0L);
            FAILURES.set(0L);
            TRAVERSALS_LAST_MINUTE.clear();
            FAILURES_LAST_MINUTE.clear();
            FailureRegistry.clear();
            windowStartMs = 0L;
            windowBlockChanges = 0L;
            windowPackets = 0L;
            windowRenderNanos = 0L;
            blockChangesPerSecond = 0D;
            packetsPerSecond = 0D;
            renderMsPerSecond = 0D;
        } finally {
            RATE_GATE.set(false);
        }
        setProjectionGauges(0, 0, 0);
    }

    private static void refreshRates(long now) {
        long observedStart = windowStartMs;

        if (observedStart != 0L && now - observedStart < RATE_WINDOW_MS) {
            return;
        }

        if (!RATE_GATE.compareAndSet(false, true)) {
            return;
        }

        try {
            long windowStart = windowStartMs;

            if (windowStart == 0L) {
                windowBlockChanges = BLOCK_CHANGES.get();
                windowPackets = PACKETS.get();
                windowRenderNanos = RENDER_NANOS.get();
                windowStartMs = now;
                return;
            }

            long elapsed = now - windowStart;
            if (elapsed < RATE_WINDOW_MS) {
                return;
            }

            long blockChanges = BLOCK_CHANGES.get();
            long packets = PACKETS.get();
            long renderNanos = RENDER_NANOS.get();
            double seconds = elapsed / 1000D;

            blockChangesPerSecond = (blockChanges - windowBlockChanges) / seconds;
            packetsPerSecond = (packets - windowPackets) / seconds;
            renderMsPerSecond = ((renderNanos - windowRenderNanos) / 1.0E6D) / seconds;

            windowStartMs = now;
            windowBlockChanges = blockChanges;
            windowPackets = packets;
            windowRenderNanos = renderNanos;
        } finally {
            RATE_GATE.set(false);
        }
    }
}
