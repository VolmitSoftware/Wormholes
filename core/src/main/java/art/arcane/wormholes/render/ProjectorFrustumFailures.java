package art.arcane.wormholes.render;

import art.arcane.wormholes.service.WormholesTelemetry;

final class ProjectorFrustumFailures {
    static final int CONSECUTIVE_LIMIT = 3;
    static final String FAILURE_REASON = "RENDER_FRUSTUM_BUILD_FAILED";

    private int consecutive;
    private int total;

    int recordFailure() {
        consecutive++;
        total++;
        WormholesTelemetry.countFailure(FAILURE_REASON);
        return consecutive;
    }

    void recordSuccess() {
        if (consecutive != 0) {
            consecutive = 0;
        }
    }

    int consecutive() {
        return consecutive;
    }

    int total() {
        return total;
    }

    static boolean exhausted(int consecutiveFailures) {
        return consecutiveFailures >= CONSECUTIVE_LIMIT;
    }
}
