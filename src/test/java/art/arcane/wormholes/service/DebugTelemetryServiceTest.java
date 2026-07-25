package art.arcane.wormholes.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugTelemetryServiceTest {
    @BeforeEach
    void resetTelemetry() {
        WormholesTelemetry.clear();
    }

    @AfterEach
    void clearTelemetry() {
        WormholesTelemetry.clear();
    }

    @Test
    void ratesUseTheMeasuredElapsedTime() {
        DebugTelemetryService.CounterSnapshot previous = counters(1_000_000_000L, 0L);
        DebugTelemetryService.CounterSnapshot current = new DebugTelemetryService.CounterSnapshot(
            3_000_000_000L,
            2L,
            4L,
            6L,
            8L,
            10L,
            12L,
            14L,
            16L,
            18L,
            20L,
            22L,
            24L,
            26L,
            28L,
            30L,
            32L,
            34L
        );

        DebugTelemetryService.RateSnapshot rates = DebugTelemetryService.RateSnapshot.between(previous, current);

        assertEquals(2.0D, rates.elapsedSeconds());
        assertEquals(1.0D, rates.rawBytesInPerSecond());
        assertEquals(2.0D, rates.wireBytesInPerSecond());
        assertEquals(3.0D, rates.rawBytesOutPerSecond());
        assertEquals(4.0D, rates.wireBytesOutPerSecond());
        assertEquals(5.0D, rates.viewBulkPerSecond());
        assertEquals(6.0D, rates.viewDiffPerSecond());
        assertEquals(7.0D, rates.viewEntityPerSecond());
        assertEquals(8.0D, rates.viewTimePerSecond());
        assertEquals(9.0D, rates.replicatedBlocksPerSecond());
        assertEquals(10.0D, rates.resyncPerSecond());
        assertEquals(11.0D, rates.transfersCompletedPerSecond());
        assertEquals(12.0D, rates.transfersFailedPerSecond());
        assertEquals(13.0D, rates.sidebandDroppedBytesPerSecond());
        assertEquals(14.0D, rates.sidebandDroppedCountPerSecond());
        assertEquals(15.0D, rates.captureDroppedPerSecond());
        assertEquals(16.0D, rates.captureOverflowPerSecond());
        assertEquals(17.0D, rates.doorTransitsFailedPerSecond());
    }

    @Test
    void ratesClampCountersThatReset() {
        DebugTelemetryService.CounterSnapshot previous = counters(1_000_000_000L, 100L);
        DebugTelemetryService.CounterSnapshot current = counters(2_000_000_000L, 5L);

        DebugTelemetryService.RateSnapshot rates = DebugTelemetryService.RateSnapshot.between(previous, current);

        assertEquals(0.0D, rates.rawBytesOutPerSecond());
        assertEquals(0.0D, rates.viewDiffPerSecond());
        assertEquals(0.0D, rates.captureOverflowPerSecond());
    }

    @Test
    void ratesAreZeroWhenTimeDoesNotAdvance() {
        DebugTelemetryService.CounterSnapshot previous = counters(1_000_000_000L, 10L);
        DebugTelemetryService.CounterSnapshot current = counters(1_000_000_000L, 20L);

        DebugTelemetryService.RateSnapshot rates = DebugTelemetryService.RateSnapshot.between(previous, current);

        assertEquals(DebugTelemetryService.RateSnapshot.zero(), rates);
    }

    @Test
    void failureLineAlwaysRendersTheTotalAndTheRate() {
        WormholesTelemetry.failuresPerMinute(1_000L);
        WormholesTelemetry.countFailure("TRAVERSAL_SOURCE_BOUNCE_SCHEDULE_REJECTED");
        WormholesTelemetry.countFailure("TRAVERSAL_SOURCE_BOUNCE_SCHEDULE_REJECTED");

        String line = DebugTelemetryService.failureLine(2_000L, 4L, 0.5D, 1L, 0.4D);

        assertTrue(line.startsWith("[debug/failures] plugin=2 (+120.0/min)"), line);
        assertTrue(line.contains("traversal=4 (+0.5/s)"), line);
        assertTrue(line.contains("doors=1 (+0.4/s)"), line);
    }

    @Test
    void failureLineNeverSpamsThePerReasonBreakdown() {
        for (int i = 0; i < 40; i++) {
            WormholesTelemetry.countFailure("TRAVERSAL_SYNTHETIC_REASON_" + i);
        }

        String line = DebugTelemetryService.failureLine(1_000L, 0L, 0.0D, 0L, 0.0D);

        assertEquals(-1, line.indexOf('\n'), "the per-second line must stay a single line");
        assertFalse(line.contains("TRAVERSAL_SYNTHETIC_REASON_"), "reason keys must not be rendered per second");
        assertTrue(line.contains("plugin=40"), line);
        assertTrue(line.contains("pluginReasons=40"), line);
    }

    @Test
    void sampleFailuresAreCountedInsteadOfVanishing() {
        assertFalse(DebugTelemetryService.runSample(quietLogger(), () -> {
            throw new IllegalStateException("boom");
        }));

        assertEquals(1L, WormholesTelemetry.failures());
        assertEquals(Long.valueOf(1L), WormholesTelemetry.failureBreakdown().get("DEBUG_TELEMETRY_SAMPLE_FAILED"));
    }

    @Test
    void healthySamplesCountNoFailure() {
        assertTrue(DebugTelemetryService.runSample(quietLogger(), () -> {
        }));
        assertEquals(0L, WormholesTelemetry.failures());
    }

    @Test
    void missingSchedulerRuntimeCountsAFailure() {
        assertFalse(DebugTelemetryService.schedulerAvailable(null, quietLogger()));
        assertEquals(1L, WormholesTelemetry.failures());
        assertEquals(Long.valueOf(1L), WormholesTelemetry.failureBreakdown().get("DEBUG_TELEMETRY_SCHEDULER_UNAVAILABLE"));
    }

    private static Logger quietLogger() {
        Logger logger = Logger.getLogger("DebugTelemetryServiceTest");
        logger.setLevel(Level.OFF);
        return logger;
    }

    private static DebugTelemetryService.CounterSnapshot counters(long capturedAtNanos, long value) {
        return new DebugTelemetryService.CounterSnapshot(
            capturedAtNanos,
            value,
            value,
            value,
            value,
            value,
            value,
            value,
            value,
            value,
            value,
            value,
            value,
            value,
            value,
            value,
            value,
            value
        );
    }
}
