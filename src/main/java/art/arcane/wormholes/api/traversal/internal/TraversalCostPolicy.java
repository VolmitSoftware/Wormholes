package art.arcane.wormholes.api.traversal.internal;

public record TraversalCostPolicy(boolean enabled, TraversalCostFailureMode failureMode, int providerFaultLimit,
                                  long slowProviderMillis) {
    public static final boolean DEFAULT_ENABLED = true;
    public static final String DEFAULT_FAILURE_MODE = "allow";
    public static final int DEFAULT_PROVIDER_FAULT_LIMIT = 5;
    public static final long DEFAULT_SLOW_PROVIDER_MILLIS = 5L;
    public static final int MAX_PROVIDER_FAULT_LIMIT = 1000;
    public static final long MAX_SLOW_PROVIDER_MILLIS = 60_000L;

    private static final TraversalCostPolicy DEFAULTS = new TraversalCostPolicy(DEFAULT_ENABLED,
        TraversalCostFailureMode.ALLOW, DEFAULT_PROVIDER_FAULT_LIMIT, DEFAULT_SLOW_PROVIDER_MILLIS);

    public TraversalCostPolicy {
        failureMode = failureMode == null ? TraversalCostFailureMode.ALLOW : failureMode;
        providerFaultLimit = Math.max(0, Math.min(MAX_PROVIDER_FAULT_LIMIT, providerFaultLimit));
        slowProviderMillis = Math.max(0L, Math.min(MAX_SLOW_PROVIDER_MILLIS, slowProviderMillis));
    }

    public static TraversalCostPolicy defaults() {
        return DEFAULTS;
    }

    public static TraversalCostPolicy of(boolean enabled, String failureMode, int providerFaultLimit,
                                         long slowProviderMillis) {
        return new TraversalCostPolicy(enabled, TraversalCostFailureMode.parse(failureMode), providerFaultLimit,
            slowProviderMillis);
    }

    public boolean failClosed() {
        return failureMode == TraversalCostFailureMode.DENY;
    }

    public boolean quarantineEnabled() {
        return providerFaultLimit > 0;
    }

    public boolean watchdogEnabled() {
        return slowProviderMillis > 0L;
    }
}
