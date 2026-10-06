package art.arcane.optics.spi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OpticsMetricsTest {
    @Test
    void noneIsASharedSinkThatIgnoresEveryCounter() {
        OpticsMetrics metrics = OpticsMetrics.none();
        metrics.failure("RENDER_FRUSTUM_BUILD_FAILED");
        metrics.packet();
        metrics.count("cells", 12L);
        assertSame(metrics, OpticsMetrics.none());
    }

    @Test
    void noneStillProvidesAMonotonicClock() {
        OpticsMetrics metrics = OpticsMetrics.none();
        long first = metrics.nanoTime();
        long second = metrics.nanoTime();
        assertTrue(second >= first);
    }
}
