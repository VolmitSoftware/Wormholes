package art.arcane.wormholes.chunk.presend;

public enum ChunkPreSendOutcome {
    PRE_SENT(null, true),
    PRE_SENT_PARTIAL(null, true),
    SKIPPED_DISABLED(null, false),
    SKIPPED_UNSUPPORTED_PLATFORM("PRESEND_UNSUPPORTED_PLATFORM", false),
    SKIPPED_PLAYER_OFFLINE("PRESEND_PLAYER_OFFLINE", false),
    SKIPPED_CROSS_SERVER("PRESEND_CROSS_SERVER", false),
    SKIPPED_NO_BUDGET("PRESEND_NO_BUDGET", false),
    SKIPPED_DESTINATION_UNLOADED("PRESEND_DESTINATION_UNLOADED", false),
    SKIPPED_REGION_NOT_OWNED("PRESEND_REGION_NOT_OWNED", false),
    SKIPPED_NO_CHUNKS("PRESEND_NO_CHUNKS", false),
    SKIPPED_ALREADY_PRESENT(null, false),
    FAILED_VIEW_CENTER("PRESEND_VIEW_CENTER_FAILED", false);

    private final String telemetryReason;
    private final boolean delivered;

    ChunkPreSendOutcome(String telemetryReason, boolean delivered) {
        this.telemetryReason = telemetryReason;
        this.delivered = delivered;
    }

    public String telemetryReason() {
        return telemetryReason;
    }

    public boolean delivered() {
        return delivered;
    }
}
