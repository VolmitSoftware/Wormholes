package art.arcane.wormholes.api.traversal;

public enum TraversalOutcome {
    DISABLED(true),
    ALLOWED_FREE(true),
    ALLOWED_CHARGED(true),
    ALLOWED_PROVIDER_FAILED(true),
    DENIED_BY_LISTENER(false),
    DENIED_BY_PROVIDER(false),
    DENIED_INSUFFICIENT(false),
    DENIED_PROVIDER_FAILED(false),
    DENIED_IN_PROGRESS(false),
    DENIED_REENTRANT(false);

    private final boolean allowed;

    TraversalOutcome(boolean allowed) {
        this.allowed = allowed;
    }

    public boolean allowed() {
        return allowed;
    }
}
