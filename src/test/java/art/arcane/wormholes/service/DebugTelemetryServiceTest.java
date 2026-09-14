package art.arcane.wormholes.service;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.logging.Level;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugTelemetryServiceTest {
    @BeforeEach
    void resetTelemetry() {
        WormholesTelemetry.clear();
    }

    @AfterEach
    void clearTelemetry() {
        WormholesTelemetry.clear();
        Settings.DEBUG = false;
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
    void enablingReportsExistingReasonsWithTheirLatestDetailsOnce() {
        RecordingHandler handler = new RecordingHandler();
        DebugTelemetryService service = new DebugTelemetryService(null, recordingLogger(handler));
        FailureRegistry.record("HANDOFF_TIMED_OUT", "peer=old", 1_000L);
        FailureRegistry.record("HANDOFF_TIMED_OUT", "peer=beta portal=hub", 2_000L);

        service.logFailureReasons();
        assertTrue(handler.records.isEmpty());
        Settings.DEBUG = true;
        service.logFailureReasons();

        assertEquals(1, handler.records.size());
        assertEquals("[debug/failure] reason=HANDOFF_TIMED_OUT total=2 new=2"
            + " lastSeen=1970-01-01T00:00:02Z detail=peer=beta portal=hub", handler.records.getFirst().getMessage());
        service.logFailureReasons();
        assertEquals(1, handler.records.size());
    }

    @Test
    void onlyChangedReasonsAreReportedAgain() {
        RecordingHandler handler = new RecordingHandler();
        DebugTelemetryService service = new DebugTelemetryService(null, recordingLogger(handler));
        Settings.DEBUG = true;
        FailureRegistry.record("ACCESS_DENIED", "portal=old", 1_000L);
        FailureRegistry.record("HANDOFF_TIMED_OUT", "peer=beta", 1_000L);
        service.logFailureReasons();
        handler.records.clear();

        FailureRegistry.record("ACCESS_DENIED", "portal=hub", 2_000L);
        service.logFailureReasons();

        assertEquals(1, handler.records.size());
        assertEquals("[debug/failure] reason=ACCESS_DENIED total=2 new=1"
            + " lastSeen=1970-01-01T00:00:02Z detail=portal=hub", handler.records.getFirst().getMessage());
        service.logFailureReasons();
        assertEquals(1, handler.records.size());
    }

    @Test
    void reEnablingReportsTheCurrentBreakdownAgain() {
        RecordingHandler handler = new RecordingHandler();
        DebugTelemetryService service = new DebugTelemetryService(null, recordingLogger(handler));
        FailureRegistry.record("ACCESS_DENIED", "portal=hub", 1_000L);
        assertTrue(service.toggle("tester"));
        service.logFailureReasons();
        assertFalse(service.toggle("tester"));
        handler.records.clear();
        service.logFailureReasons();
        assertTrue(handler.records.isEmpty());

        assertTrue(service.toggle("tester"));
        handler.records.clear();
        service.logFailureReasons();

        assertEquals(1, handler.records.size());
        assertTrue(handler.records.getFirst().getMessage().contains("reason=ACCESS_DENIED total=1 new=1"));
    }

    @Test
    void resetCountersAreReportedWithoutNegativeDeltas() {
        RecordingHandler handler = new RecordingHandler();
        DebugTelemetryService service = new DebugTelemetryService(null, recordingLogger(handler));
        Settings.DEBUG = true;
        FailureRegistry.record("ACCESS_DENIED", "portal=old", 1_000L);
        FailureRegistry.record("ACCESS_DENIED", "portal=old", 1_001L);
        service.logFailureReasons();
        handler.records.clear();
        FailureRegistry.clear();
        FailureRegistry.record("ACCESS_DENIED", "portal=new", 2_000L);

        service.logFailureReasons();

        assertEquals(1, handler.records.size());
        assertTrue(handler.records.getFirst().getMessage().contains("total=1 new=1"));
        assertTrue(handler.records.getFirst().getMessage().endsWith("detail=portal=new"));
    }

    @Test
    void reasonAndDetailLineBreaksCannotCreateExtraConsoleLines() {
        RecordingHandler handler = new RecordingHandler();
        DebugTelemetryService service = new DebugTelemetryService(null, recordingLogger(handler));
        Settings.DEBUG = true;
        FailureRegistry.record("ACCESS\nDENIED", "peer=beta\r\nportal=hub\tname=one\u0085two\u2028three\u2029four", 1_000L);

        service.logFailureReasons();

        assertEquals(1, handler.records.size());
        String line = handler.records.getFirst().getMessage();
        assertTrue(line.contains("reason=ACCESS DENIED"), line);
        assertTrue(line.endsWith("detail=peer=beta  portal=hub name=one two three four"), line);
        assertFalse(line.chars().anyMatch(character -> character == '\n' || character == '\r'
            || character == '\t' || character == '\u0085' || character == '\u2028' || character == '\u2029'));
    }

    @Test
    void countersRemainAvailableWhenTheirDetailHasLeftTheRecentRing() {
        RecordingHandler handler = new RecordingHandler();
        DebugTelemetryService service = new DebugTelemetryService(null, recordingLogger(handler));
        Settings.DEBUG = true;
        FailureRegistry.record("OLDER", "expired", 1_000L);
        for (int index = 0; index < FailureRegistry.RING_CAPACITY; index++) {
            FailureRegistry.record("RECENT", "index=" + index, 2_000L + index);
        }

        service.logFailureReasons();

        assertEquals(2, handler.records.size());
        assertEquals("[debug/failure] reason=OLDER total=1 new=1"
            + " lastSeen=1970-01-01T00:00:01Z detail=-", handler.records.getFirst().getMessage());
        assertTrue(handler.records.get(1).getMessage().endsWith("detail=index=255"));
    }

    @Test
    void sampleFailureLoggingPreservesTheOriginalException() {
        RecordingHandler handler = new RecordingHandler();
        IllegalStateException failure = new IllegalStateException("sample failure");

        assertFalse(DebugTelemetryService.runSample(recordingLogger(handler), () -> {
            throw failure;
        }));

        assertEquals(1, handler.records.size());
        assertSame(failure, handler.records.getFirst().getThrown());
        assertEquals(Level.WARNING, handler.records.getFirst().getLevel());
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
    void settingsReloadClearsRuntimeOverrideAndAppliesFileVerboseLogging() {
        DebugTelemetryService service = new DebugTelemetryService(null, quietLogger());
        Settings.DEBUG = false;
        assertTrue(service.toggle("tester"));
        assertTrue(Settings.DEBUG);

        MainConfig main = new MainConfig();
        main.verboseLogging = false;
        Settings.refresh(new WormholesSettings(main, new ProjectionConfig(), new RenderConfig(), new NetworkConfig()));
        service.onSettingsReloaded();

        assertFalse(Settings.DEBUG);

        main.verboseLogging = true;
        Settings.refresh(new WormholesSettings(main, new ProjectionConfig(), new RenderConfig(), new NetworkConfig()));
        service.onSettingsReloaded();

        assertTrue(Settings.DEBUG);
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

    private static Logger recordingLogger(RecordingHandler handler) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
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

    private static final class RecordingHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<LogRecord>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
