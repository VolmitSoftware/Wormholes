package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.service.WormholesTelemetry;
import art.arcane.optics.scan.FrustumFailures;

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
        FrustumFailures failures = new FrustumFailures(WormholesTelemetry.metrics());

        assertFalse(FrustumFailures.exhausted(failures.recordFailure()),
            "the first failure must not close a projector that may recover");
        assertFalse(FrustumFailures.exhausted(failures.recordFailure()),
            "the second failure must not close a projector that may recover");
        assertTrue(FrustumFailures.exhausted(failures.recordFailure()),
            "a structure that always throws must stop the pass loop instead of spinning forever");
        assertEquals(3, failures.total(), "every terminal failure must be counted for reporting");
    }

    @Test
    public void aRecoveredPassClearsTheConsecutiveFailureRun() {
        FrustumFailures failures = new FrustumFailures(WormholesTelemetry.metrics());

        failures.recordFailure();
        failures.recordFailure();
        failures.recordSuccess();

        assertEquals(0, failures.consecutive(), "a successful frustum build must clear the failure run");
        assertFalse(FrustumFailures.exhausted(failures.recordFailure()),
            "isolated failures separated by good passes must never close a healthy projector");
        assertEquals(3, failures.total(), "the lifetime failure count must survive recovery");
    }

    @Test
    public void repeatedSuccessesDoNotDisturbTheFailureCounters() {
        FrustumFailures failures = new FrustumFailures(WormholesTelemetry.metrics());

        failures.recordSuccess();
        failures.recordSuccess();

        assertEquals(0, failures.consecutive());
        assertEquals(0, failures.total());
        assertEquals(0L, WormholesTelemetry.failures(), "a healthy projector must never register a failure");
    }

    @Test
    public void everyFrustumBuildFailureReachesTheSharedTerminalFailureCounter() {
        FrustumFailures failures = new FrustumFailures(WormholesTelemetry.metrics());

        failures.recordFailure();
        failures.recordFailure();
        failures.recordSuccess();
        failures.recordFailure();

        assertEquals(3, failures.total(), "the subsystem ledger must still own the local count");
        assertEquals(3L, WormholesTelemetry.failures(),
            "the render terminal failure must also be visible on the plugin wide counter, not just in verbose diagnostics");
        assertEquals(Map.of(FrustumFailures.FAILURE_REASON, Long.valueOf(3L)),
            WormholesTelemetry.failureBreakdown(),
            "the failure must be attributed to a stable, greppable reason");
    }

    @Test
    public void theFrustumFailureReasonIsAStableSubsystemPrefixedToken() {
        assertEquals("RENDER_FRUSTUM_BUILD_FAILED", FrustumFailures.FAILURE_REASON);
    }

    @Test
    public void theClosingFailureIsCountedExactlyOnceAndNotAgainAtTheProjectorLevel() {
        FrustumFailures failures = new FrustumFailures(WormholesTelemetry.metrics());

        int consecutive = 0;
        while (!FrustumFailures.exhausted(consecutive)) {
            consecutive = failures.recordFailure();
        }

        assertEquals(FrustumFailures.CONSECUTIVE_LIMIT, failures.total(),
            "the run that closes the projector must not be double counted at two levels of the same call chain");
        assertEquals((long) FrustumFailures.CONSECUTIVE_LIMIT, WormholesTelemetry.failures(),
            "the run that closes the projector must not be double counted at two levels of the same call chain");
    }
}
