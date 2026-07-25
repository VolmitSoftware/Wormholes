package art.arcane.wormholes.api.traversal.internal;

public enum TraversalSettlement {
    COMMITTED(true),
    REFUNDED(true),
    NOT_OPEN(false),
    DISABLED(false);

    private final boolean settled;

    TraversalSettlement(boolean settled) {
        this.settled = settled;
    }

    public boolean settled() {
        return settled;
    }
}
