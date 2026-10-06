package art.arcane.wormholes.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.optics.spi.OpticsMetrics;

final class OpticsMetricsAdapterTest {
    private final OpticsMetrics metrics = WormholesTelemetry.metrics();

    @BeforeEach
    void setUp() {
        WormholesTelemetry.clear();
    }

    @AfterEach
    void tearDown() {
        WormholesTelemetry.clear();
    }

    @Test
    void theAdapterIsShared() {
        assertSame(metrics, WormholesTelemetry.metrics());
    }

    @Test
    void failuresReachTheFailureRegistry() {
        metrics.failure("OPTICS_TEST_FAILURE");
        metrics.failure("OPTICS_TEST_FAILURE");
        assertEquals(2L, WormholesTelemetry.failures());
        assertEquals(Long.valueOf(2L), WormholesTelemetry.failureBreakdown().get("OPTICS_TEST_FAILURE"));
    }

    @Test
    void packetsReachThePacketRate() {
        WormholesTelemetry.packetsPerSecond(1_000L);
        metrics.packet();
        metrics.packet();
        metrics.packet();
        assertEquals(3.0D, WormholesTelemetry.packetsPerSecond(2_000L));
    }

    @Test
    void countsAccumulatePerKey() {
        metrics.count("optics.test.first", 3L);
        metrics.count("optics.test.first", 4L);
        metrics.count("optics.test.second", 1L);
        assertEquals(7L, WormholesTelemetry.counter("optics.test.first"));
        assertEquals(1L, WormholesTelemetry.counter("optics.test.second"));
        assertEquals(0L, WormholesTelemetry.counter("optics.test.missing"));
    }

    @Test
    void zeroDeltasAndNullKeysAreIgnored() {
        metrics.count("optics.test.zero", 0L);
        metrics.count(null, 5L);
        assertEquals(0L, WormholesTelemetry.counter("optics.test.zero"));
        assertEquals(0L, WormholesTelemetry.counter(null));
    }

    @Test
    void clearResetsTheCounters() {
        metrics.count("optics.test.cleared", 9L);
        WormholesTelemetry.clear();
        assertEquals(0L, WormholesTelemetry.counter("optics.test.cleared"));
    }

    @Test
    void nanoTimeAdvancesWithTheSystemClock() {
        long before = System.nanoTime();
        long sampled = metrics.nanoTime();
        assertTrue(sampled >= before);
        assertTrue(sampled <= System.nanoTime());
    }
}
