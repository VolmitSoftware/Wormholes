package art.arcane.optics.scan;

import art.arcane.optics.spi.OpticsMetrics;

public final class FrustumFailures {
    public static final int CONSECUTIVE_LIMIT = 3;
    public static final String FAILURE_REASON = "RENDER_FRUSTUM_BUILD_FAILED";

    private final OpticsMetrics metrics;
    private int consecutive;
    private int total;

    public FrustumFailures(OpticsMetrics metrics) {
        this.metrics = metrics;
    }

    public static boolean exhausted(int consecutiveFailures) {
        return consecutiveFailures >= CONSECUTIVE_LIMIT;
    }

    public int recordFailure() {
        consecutive++;
        total++;
        metrics.failure(FAILURE_REASON);
        return consecutive;
    }

    public void recordSuccess() {
        if (consecutive != 0) {
            consecutive = 0;
        }
    }

    public int consecutive() {
        return consecutive;
    }

    public int total() {
        return total;
    }
}
