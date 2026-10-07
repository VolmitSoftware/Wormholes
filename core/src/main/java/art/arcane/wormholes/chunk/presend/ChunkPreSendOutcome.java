package art.arcane.wormholes.chunk.presend;

public enum ChunkPreSendOutcome {
    PRE_SENT(null, true),
    PRE_SENT_PARTIAL(null, true),
    SKIPPED_DISABLED(null, false),
    SKIPPED_UNSUPPORTED_PLATFORM(null, false),
    SKIPPED_PLAYER_OFFLINE(null, false),
    SKIPPED_CROSS_SERVER(null, false),
    SKIPPED_NO_BUDGET(null, false),
    SKIPPED_DESTINATION_UNLOADED(null, false),
    /** The destination dimension is a different height, so its chunks would not decode on a client that has not moved yet. */
    SKIPPED_DIMENSION_MISMATCH(null, false),
    SKIPPED_REGION_NOT_OWNED(null, false),
    SKIPPED_NO_CHUNKS("PRESEND_NO_CHUNKS", false),
    SKIPPED_ALREADY_PRESENT(null, false),
    FAILED_VIEW_CENTER("PRESEND_VIEW_CENTER_FAILED", false),
    /** A send stalled past the hard stop; everything half-sent was rolled back and the traversal proceeds without pre-send. */
    ROLLED_BACK_BUDGET_OVERRUN("PRESEND_BUDGET_OVERRUN", false);

    private final String failureReason;
    private final boolean delivered;

    ChunkPreSendOutcome(String failureReason, boolean delivered) {
        this.failureReason = failureReason;
        this.delivered = delivered;
    }

    public String failureReason() {
        return failureReason;
    }

    public boolean delivered() {
        return delivered;
    }
}
