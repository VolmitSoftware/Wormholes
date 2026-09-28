package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.service.WormholesTelemetry;

public final class PortalProjectorFrustumFailureTest {
    @BeforeEach
    public void resetTelemetry() {
        WormholesTelemetry.clear();
    }

    @AfterEach
    public void clearTelemetry() {
        WormholesTelemetry.clear();
    }

    @Test
    public void aProjectorThatCannotBuildItsFrustumIsGivenUpOnInsteadOfRetriedForever() {
        ProjectorFrustumFailures failures = new ProjectorFrustumFailures();

        assertFalse(ProjectorFrustumFailures.exhausted(failures.recordFailure()),
            "the first failure must not close a projector that may recover");
        assertFalse(ProjectorFrustumFailures.exhausted(failures.recordFailure()),
            "the second failure must not close a projector that may recover");
        assertTrue(ProjectorFrustumFailures.exhausted(failures.recordFailure()),
            "a structure that always throws must stop the pass loop instead of spinning forever");
        assertEquals(3, failures.total(), "every terminal failure must be counted for reporting");
    }

    @Test
    public void aRecoveredPassClearsTheConsecutiveFailureRun() {
        ProjectorFrustumFailures failures = new ProjectorFrustumFailures();

        failures.recordFailure();
        failures.recordFailure();
        failures.recordSuccess();

        assertEquals(0, failures.consecutive(), "a successful frustum build must clear the failure run");
        assertFalse(ProjectorFrustumFailures.exhausted(failures.recordFailure()),
            "isolated failures separated by good passes must never close a healthy projector");
        assertEquals(3, failures.total(), "the lifetime failure count must survive recovery");
    }

    @Test
    public void repeatedSuccessesDoNotDisturbTheFailureCounters() {
        ProjectorFrustumFailures failures = new ProjectorFrustumFailures();

        failures.recordSuccess();
        failures.recordSuccess();

        assertEquals(0, failures.consecutive());
        assertEquals(0, failures.total());
        assertEquals(0L, WormholesTelemetry.failures(), "a healthy projector must never register a failure");
    }

    @Test
    public void everyFrustumBuildFailureReachesTheSharedTerminalFailureCounter() {
        ProjectorFrustumFailures failures = new ProjectorFrustumFailures();

        failures.recordFailure();
        failures.recordFailure();
        failures.recordSuccess();
        failures.recordFailure();

        assertEquals(3, failures.total(), "the subsystem ledger must still own the local count");
        assertEquals(3L, WormholesTelemetry.failures(),
            "the render terminal failure must also be visible on the plugin wide counter, not just in verbose diagnostics");
        assertEquals(Map.of(ProjectorFrustumFailures.FAILURE_REASON, Long.valueOf(3L)),
            WormholesTelemetry.failureBreakdown(),
            "the failure must be attributed to a stable, greppable reason");
    }

    @Test
    public void theFrustumFailureReasonIsAStableSubsystemPrefixedToken() {
        assertEquals("RENDER_FRUSTUM_BUILD_FAILED", ProjectorFrustumFailures.FAILURE_REASON);
    }

    @Test
    public void theClosingFailureIsCountedExactlyOnceAndNotAgainAtTheProjectorLevel() {
        ProjectorFrustumFailures failures = new ProjectorFrustumFailures();

        int consecutive = 0;
        while (!ProjectorFrustumFailures.exhausted(consecutive)) {
            consecutive = failures.recordFailure();
        }

        assertEquals(ProjectorFrustumFailures.CONSECUTIVE_LIMIT, failures.total(),
            "the run that closes the projector must not be double counted at two levels of the same call chain");
        assertEquals((long) ProjectorFrustumFailures.CONSECUTIVE_LIMIT, WormholesTelemetry.failures(),
            "the run that closes the projector must not be double counted at two levels of the same call chain");
    }
}
