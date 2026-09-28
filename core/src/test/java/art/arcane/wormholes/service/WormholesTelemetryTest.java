package art.arcane.wormholes.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WormholesTelemetryTest {
    @BeforeEach
    void setUp() {
        WormholesTelemetry.clear();
    }

    @AfterEach
    void tearDown() {
        WormholesTelemetry.clear();
    }

    @Test
    void blockChangesDoNotImplicitlyCountPackets() {
        primeRateWindow();

        WormholesTelemetry.countBlockChange();
        WormholesTelemetry.countBlockChange();
        WormholesTelemetry.countBlockChange();

        assertEquals(3.0D, WormholesTelemetry.blockChangesPerSecond(2_000L));
        assertEquals(0.0D, WormholesTelemetry.packetsPerSecond(2_000L));
    }

    @Test
    void packetsAndBlockChangesAreCountedIndependently() {
        primeRateWindow();

        WormholesTelemetry.countBlockChange();
        WormholesTelemetry.countBlockChange();
        WormholesTelemetry.countBlockChange();
        WormholesTelemetry.countPacket();
        WormholesTelemetry.countPacket();

        assertEquals(3.0D, WormholesTelemetry.blockChangesPerSecond(2_000L));
        assertEquals(2.0D, WormholesTelemetry.packetsPerSecond(2_000L));
    }

    @Test
    void failureReasonCountTracksDistinctReasonsWithoutBuildingTheBreakdown() {
        WormholesTelemetry.countFailure("TRAVERSAL_SOURCE_BOUNCE_SCHEDULE_REJECTED");
        WormholesTelemetry.countFailure("TRAVERSAL_SOURCE_BOUNCE_SCHEDULE_REJECTED");
        WormholesTelemetry.countFailure("DOOR_TRANSIT_SCHEDULE_REJECTED");

        assertEquals(3L, WormholesTelemetry.failures());
        assertEquals(2, WormholesTelemetry.failureReasonCount());
        assertEquals(WormholesTelemetry.failureBreakdown().size(), WormholesTelemetry.failureReasonCount());
    }

    @Test
    void failuresPerMinuteReportsTheWindowedFailureRate() {
        primeRateWindow();

        WormholesTelemetry.countFailure("TRAVERSAL_SOURCE_BOUNCE_SCHEDULE_REJECTED");
        WormholesTelemetry.countFailure("DOOR_TRANSIT_SCHEDULE_REJECTED");
        WormholesTelemetry.countFailure("DOOR_TRANSIT_SCHEDULE_REJECTED");

        assertEquals(180.0D, WormholesTelemetry.failuresPerMinute(2_000L));
        assertEquals(3L, WormholesTelemetry.failures());
    }

    @Test
    void concurrentRateRefreshesLeaveTheWindowUsableForTheNextReader() throws Exception {
        int threads = 8;
        int iterations = 20_000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int index = 0; index < threads; index++) {
            Thread reader = new Thread(() -> {
                try {
                    start.await();
                    for (int iteration = 0; iteration < iterations; iteration++) {
                        WormholesTelemetry.countFailure("CONCURRENT");
                        WormholesTelemetry.failuresPerMinute(System.currentTimeMillis());
                        WormholesTelemetry.blockChangesPerSecond(System.currentTimeMillis());
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            reader.setDaemon(true);
            reader.start();
        }

        start.countDown();
        assertTrue(done.await(30L, TimeUnit.SECONDS), "concurrent rate readers did not finish");
        assertEquals((long) threads * iterations, WormholesTelemetry.failures());

        long baseline = System.currentTimeMillis() + 10_000L;
        WormholesTelemetry.failuresPerMinute(baseline);
        WormholesTelemetry.countFailure("AFTERWARDS");
        WormholesTelemetry.countFailure("AFTERWARDS");

        assertEquals(120.0D, WormholesTelemetry.failuresPerMinute(baseline + 1_000L));
    }

    @Test
    void clearingTelemetryDuringARateRefreshNeverPublishesANegativeRate() throws Exception {
        int readers = 4;
        int rounds = 20_000;
        AtomicLong clock = new AtomicLong(1_000L);
        AtomicReference<String> negative = new AtomicReference<String>();
        AtomicBoolean clearing = new AtomicBoolean(true);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(readers);

        for (int index = 0; index < readers; index++) {
            Thread reader = new Thread(() -> {
                try {
                    start.await();
                    for (int round = 0; round < rounds && negative.get() == null; round++) {
                        for (int change = 0; change < 16; change++) {
                            WormholesTelemetry.countBlockChange();
                            WormholesTelemetry.countPacket();
                            WormholesTelemetry.countTraversal();
                            WormholesTelemetry.countFailure("CONCURRENT");
                        }

                        WormholesTelemetry.addRenderNanos(1_000_000L);
                        long now = clock.addAndGet(1_000L);
                        reject(negative, "blockChangesPerSecond", WormholesTelemetry.blockChangesPerSecond(now));
                        reject(negative, "packetsPerSecond", WormholesTelemetry.packetsPerSecond(now));
                        reject(negative, "traversalsPerMinute", WormholesTelemetry.traversalsPerMinute(now));
                        reject(negative, "renderMsPerSecond", WormholesTelemetry.renderMsPerSecond(now));
                        reject(negative, "failuresPerMinute", WormholesTelemetry.failuresPerMinute(now));
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            reader.setDaemon(true);
            reader.start();
        }

        Thread reloader = new Thread(() -> {
            try {
                start.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }

            while (clearing.get()) {
                WormholesTelemetry.clear();
            }
        });
        reloader.setDaemon(true);
        reloader.start();

        start.countDown();
        assertTrue(done.await(120L, TimeUnit.SECONDS), "concurrent rate readers did not finish");
        clearing.set(false);
        reloader.join(TimeUnit.SECONDS.toMillis(30L));

        assertNull(negative.get(),
            "a hot reload calling clear() must stay mutually exclusive with refreshRates(); a rate that goes "
                + "negative is a half-applied reset being published to React and to /wormhole debug");
    }

    private static void reject(AtomicReference<String> negative, String rate, double value) {
        if (value < 0.0D) {
            negative.compareAndSet(null, rate + " published " + value);
        }
    }

    private static void primeRateWindow() {
        WormholesTelemetry.blockChangesPerSecond(1_000L);
    }
}
