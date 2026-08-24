package art.arcane.wormholes.network;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

final class TraversalFailureLedger {
    private static final long OPERATOR_WARNING_INTERVAL_MILLIS = 60_000L;

    enum Failure {
        HANDOFF_RATE_LIMITED,
        HANDOFF_PEER_UNKNOWN,
        HANDOFF_PEER_OFFLINE,
        HANDOFF_NO_DIRECT_HOST,
        HANDOFF_TRANSFER_LOCKED,
        HANDOFF_QUEUE_REJECTED,
        HANDOFF_TIMEOUT_SCHEDULE_REJECTED,
        HANDOFF_TIMED_OUT,
        HANDOFF_TIMEOUT_RETIRED,
        HANDOFF_RETREATED,
        HANDOFF_PEER_LOST,
        HANDOFF_PLAYER_OFFLINE,
        HANDOFF_DEPARTURE_INTERRUPTED,
        HANDOFF_TRANSFER_REJECTED,
        HANDOFF_DISPATCH_SCHEDULE_REJECTED,
        HANDOFF_DISPATCH_RETIRED,
        HANDOFF_DENIED,
        ENTITY_PEER_UNAVAILABLE,
        ENTITY_TRANSFER_LOCKED,
        ENTITY_SNAPSHOT_UNAVAILABLE,
        ENTITY_SNAPSHOT_TOO_LARGE,
        ENTITY_SEND_REJECTED,
        ENTITY_TIMEOUT_SCHEDULE_REJECTED,
        ENTITY_TIMED_OUT,
        ENTITY_TIMEOUT_RETIRED,
        ENTITY_DEADLINE_EXPIRED,
        ENTITY_ACK_DENIED,
        ENTITY_ARRIVAL_DENIED,
        ENTITY_ARRIVAL_PORTAL_UNAVAILABLE,
        ENTITY_ARRIVAL_SCHEDULE_REJECTED,
        ENTITY_TRANSIT_STAMP_SCHEDULE_REJECTED,
        ENTITY_TRANSIT_RESTORE_SCHEDULE_REJECTED,
        ENTITY_TRANSIT_SWEEP_SCHEDULE_REJECTED,
        SOURCE_BOUNCE_SCHEDULE_REJECTED,
        ARRIVAL_SCHEDULE_REJECTED,
        ARRIVAL_PLAYER_RETIRED,
        ARRIVAL_COMPLETION_SCHEDULE_REJECTED,
        ARRIVAL_RETRY_SCHEDULE_REJECTED,
        ARRIVAL_EXHAUSTED,
        ARRIVAL_DENIED_RETURNED,
        ARRIVAL_DENIED_STRANDED
    }

    private final AtomicLong failedTransfers = new AtomicLong();
    private final Map<Failure, AtomicLong> failureCounters = new ConcurrentHashMap<>();
    private final Map<Failure, WarningState> operatorWarnings = new ConcurrentHashMap<>();

    void record(Failure failure, UUID subjectId, String detail) {
        failedTransfers.incrementAndGet();
        failureCounters.computeIfAbsent(failure, ignored -> new AtomicLong()).incrementAndGet();
        if (debugOnly(failure)) {
            if (Settings.DEBUG) {
                Wormholes.v(formatFailure(failure, subjectId, detail));
            }
            return;
        }
        reportOperatorFailure(failure, subjectId, detail, System.currentTimeMillis());
    }

    void recordUnrecovered(Failure failure, UUID subjectId, String detail) {
        failureCounters.computeIfAbsent(failure, ignored -> new AtomicLong()).incrementAndGet();
        Wormholes.w("[traversal] UNRECOVERED " + failure.name() + " subject=" + subjectId
            + (detail == null || detail.isBlank() ? "" : " reason=" + detail));
    }

    long failed() {
        return failedTransfers.get();
    }

    Map<String, Long> breakdown() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        for (Failure failure : Failure.values()) {
            AtomicLong counter = failureCounters.get(failure);
            if (counter != null) {
                snapshot.put(failure.name(), Long.valueOf(counter.get()));
            }
        }
        return Collections.unmodifiableMap(snapshot);
    }

    static boolean debugOnly(Failure failure) {
        return switch (failure) {
            case HANDOFF_RATE_LIMITED,
                 HANDOFF_PEER_OFFLINE,
                 HANDOFF_TRANSFER_LOCKED,
                 HANDOFF_TIMEOUT_RETIRED,
                 HANDOFF_RETREATED,
                 HANDOFF_PLAYER_OFFLINE,
                 HANDOFF_DEPARTURE_INTERRUPTED,
                 HANDOFF_DENIED,
                 ENTITY_PEER_UNAVAILABLE,
                 ENTITY_TRANSFER_LOCKED,
                 ENTITY_TIMEOUT_RETIRED,
                 ENTITY_ACK_DENIED,
                 ENTITY_ARRIVAL_DENIED,
                 ARRIVAL_PLAYER_RETIRED,
                 ARRIVAL_DENIED_RETURNED -> true;
            default -> false;
        };
    }

    private void reportOperatorFailure(Failure failure, UUID subjectId, String detail, long nowMillis) {
        WarningState state = operatorWarnings.computeIfAbsent(failure, ignored -> new WarningState());
        if (!state.reserve(nowMillis)) {
            return;
        }
        long suppressed = state.suppressed.getAndSet(0L);
        Wormholes.w(formatFailure(failure, subjectId, detail)
            + (suppressed == 0L ? "" : " (" + suppressed + " similar warning(s) suppressed)"));
    }

    private static String formatFailure(Failure failure, UUID subjectId, String detail) {
        return "[traversal] FAILED " + failure.name() + " subject=" + subjectId
            + (detail == null || detail.isBlank() ? "" : " reason=" + detail);
    }

    private static final class WarningState {
        private final AtomicLong nextReportMillis = new AtomicLong();
        private final AtomicLong suppressed = new AtomicLong();

        private boolean reserve(long nowMillis) {
            while (true) {
                long nextReport = nextReportMillis.get();
                if (nowMillis < nextReport) {
                    suppressed.incrementAndGet();
                    return false;
                }
                if (nextReportMillis.compareAndSet(nextReport, nowMillis + OPERATOR_WARNING_INTERVAL_MILLIS)) {
                    return true;
                }
            }
        }
    }
}
