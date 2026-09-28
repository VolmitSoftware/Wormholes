package art.arcane.wormholes.chunk.presend;

public enum ChunkPreSendRollbackOutcome {
    NOT_NEEDED(null),
    ALREADY_CONSUMED(null),
    RESTORED(null),
    CONTINUED(null),
    PLAYER_OFFLINE("PRESEND_ROLLBACK_PLAYER_OFFLINE"),
    VIEW_CENTER_FAILED("PRESEND_ROLLBACK_VIEW_CENTER_FAILED"),
    INCOMPLETE("PRESEND_ROLLBACK_INCOMPLETE"),
    REGION_UNREACHABLE("PRESEND_ROLLBACK_REGION_UNREACHABLE"),
    SCHEDULE_REJECTED("PRESEND_ROLLBACK_SCHEDULE_REJECTED");

    private final String telemetryReason;

    ChunkPreSendRollbackOutcome(String telemetryReason) {
        this.telemetryReason = telemetryReason;
    }

    public String telemetryReason() {
        return telemetryReason;
    }
}
