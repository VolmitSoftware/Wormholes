package art.arcane.wormholes.api.traversal.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraversalCostPolicyTest {
    @Test
    void theDefaultPolicyIsEnabledAndFailsOpenSoOneBrokenPluginCannotBrickEveryPortal() {
        TraversalCostPolicy policy = TraversalCostPolicy.defaults();

        assertTrue(policy.enabled());
        assertEquals(TraversalCostFailureMode.ALLOW, policy.failureMode());
        assertFalse(policy.failClosed());
        assertEquals(TraversalCostPolicy.DEFAULT_PROVIDER_FAULT_LIMIT, policy.providerFaultLimit());
        assertEquals(TraversalCostPolicy.DEFAULT_SLOW_PROVIDER_MILLIS, policy.slowProviderMillis());
    }

    @Test
    void theFailureModeIsParsedCaseInsensitivelyAndFallsBackToAllow() {
        assertEquals(TraversalCostFailureMode.DENY, TraversalCostFailureMode.parse("deny"));
        assertEquals(TraversalCostFailureMode.DENY, TraversalCostFailureMode.parse("  DENY "));
        assertEquals(TraversalCostFailureMode.DENY, TraversalCostFailureMode.parse("fail-closed"));
        assertEquals(TraversalCostFailureMode.ALLOW, TraversalCostFailureMode.parse("allow"));
        assertEquals(TraversalCostFailureMode.ALLOW, TraversalCostFailureMode.parse("nonsense"));
        assertEquals(TraversalCostFailureMode.ALLOW, TraversalCostFailureMode.parse(null));
    }

    @Test
    void outOfRangeConfigurationValuesAreClampedRatherThanRejected() {
        TraversalCostPolicy low = TraversalCostPolicy.of(true, "allow", -7, -3L);
        TraversalCostPolicy high = TraversalCostPolicy.of(true, "allow", 999_999, 999_999L);

        assertEquals(0, low.providerFaultLimit());
        assertEquals(0L, low.slowProviderMillis());
        assertEquals(TraversalCostPolicy.MAX_PROVIDER_FAULT_LIMIT, high.providerFaultLimit());
        assertEquals(TraversalCostPolicy.MAX_SLOW_PROVIDER_MILLIS, high.slowProviderMillis());
    }

    @Test
    void aZeroFaultLimitDisablesQuarantineAndAZeroThresholdDisablesTheWatchdog() {
        TraversalCostPolicy policy = TraversalCostPolicy.of(true, "allow", 0, 0L);

        assertFalse(policy.quarantineEnabled());
        assertFalse(policy.watchdogEnabled());
        assertTrue(TraversalCostPolicy.defaults().quarantineEnabled());
        assertTrue(TraversalCostPolicy.defaults().watchdogEnabled());
    }
}
