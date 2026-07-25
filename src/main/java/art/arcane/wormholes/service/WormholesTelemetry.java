package art.arcane.wormholes.service;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public final class WormholesTelemetry {
    private static final long RATE_WINDOW_MS = 1000L;
    private static final AtomicLong BLOCK_CHANGES = new AtomicLong();
    private static final AtomicLong PACKETS = new AtomicLong();
    private static final AtomicLong TRAVERSALS = new AtomicLong();
    private static final AtomicLong RENDER_NANOS = new AtomicLong();
    private static final AtomicLong FAILURES = new AtomicLong();
    private static final ConcurrentMap<String, AtomicLong> FAILURE_REASONS = new ConcurrentHashMap<>();
    private static final Object RATE_LOCK = new Object();
    private static volatile int activeProjections;
    private static volatile int projectionObservers;
    private static volatile int spoofedEntities;
    private static long windowStartMs;
    private static long windowBlockChanges;
    private static long windowPackets;
    private static long windowTraversals;
    private static long windowRenderNanos;
    private static long windowFailures;
    private static volatile double blockChangesPerSecond;
    private static volatile double packetsPerSecond;
    private static volatile double traversalsPerMinute;
    private static volatile double renderMsPerSecond;
    private static volatile double failuresPerMinute;

    private WormholesTelemetry() {
    }

    public static void countBlockChange() {
        BLOCK_CHANGES.incrementAndGet();
    }

    public static void countPacket() {
        PACKETS.incrementAndGet();
    }

    public static void countTraversal() {
        TRAVERSALS.incrementAndGet();
    }

    public static void countFailure(String reason) {
        FAILURES.incrementAndGet();
        if (reason == null || reason.isEmpty()) {
            return;
        }

        FAILURE_REASONS.computeIfAbsent(reason, key -> new AtomicLong()).incrementAndGet();
    }

    public static long failures() {
        return FAILURES.get();
    }

    public static int failureReasonCount() {
        return FAILURE_REASONS.size();
    }

    public static Map<String, Long> failureBreakdown() {
        Map<String, Long> breakdown = new TreeMap<>();
        FAILURE_REASONS.forEach((reason, count) -> breakdown.put(reason, count.get()));
        return breakdown;
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
        refreshRates(now);
        return traversalsPerMinute;
    }

    public static double renderMsPerSecond(long now) {
        refreshRates(now);
        return renderMsPerSecond;
    }

    public static double failuresPerMinute(long now) {
        refreshRates(now);
        return failuresPerMinute;
    }

    public static void clear() {
        synchronized (RATE_LOCK) {
            BLOCK_CHANGES.set(0L);
            PACKETS.set(0L);
            TRAVERSALS.set(0L);
            RENDER_NANOS.set(0L);
            FAILURES.set(0L);
            FAILURE_REASONS.clear();
            windowStartMs = 0L;
            windowBlockChanges = 0L;
            windowPackets = 0L;
            windowTraversals = 0L;
            windowRenderNanos = 0L;
            windowFailures = 0L;
            blockChangesPerSecond = 0D;
            packetsPerSecond = 0D;
            traversalsPerMinute = 0D;
            renderMsPerSecond = 0D;
            failuresPerMinute = 0D;
        }
        setProjectionGauges(0, 0, 0);
    }

    private static void refreshRates(long now) {
        synchronized (RATE_LOCK) {
            if (windowStartMs == 0L) {
                windowStartMs = now;
                windowBlockChanges = BLOCK_CHANGES.get();
                windowPackets = PACKETS.get();
                windowTraversals = TRAVERSALS.get();
                windowRenderNanos = RENDER_NANOS.get();
                windowFailures = FAILURES.get();
                return;
            }

            long elapsed = now - windowStartMs;
            if (elapsed < RATE_WINDOW_MS) {
                return;
            }

            long blockChanges = BLOCK_CHANGES.get();
            long packets = PACKETS.get();
            long traversals = TRAVERSALS.get();
            long renderNanos = RENDER_NANOS.get();
            long failures = FAILURES.get();
            double seconds = elapsed / 1000D;

            blockChangesPerSecond = (blockChanges - windowBlockChanges) / seconds;
            packetsPerSecond = (packets - windowPackets) / seconds;
            traversalsPerMinute = ((traversals - windowTraversals) / seconds) * 60D;
            renderMsPerSecond = ((renderNanos - windowRenderNanos) / 1.0E6D) / seconds;
            failuresPerMinute = ((failures - windowFailures) / seconds) * 60D;

            windowStartMs = now;
            windowBlockChanges = blockChanges;
            windowPackets = packets;
            windowTraversals = traversals;
            windowRenderNanos = renderNanos;
            windowFailures = failures;
        }
    }
}
