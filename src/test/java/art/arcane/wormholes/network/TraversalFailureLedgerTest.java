package art.arcane.wormholes.network;

import art.arcane.wormholes.network.TraversalFailureLedger.Failure;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraversalFailureLedgerTest {
    @Test
    void aFreshLedgerReportsNothing() {
        TraversalFailureLedger ledger = new TraversalFailureLedger();

        assertEquals(0L, ledger.failed());
        assertTrue(ledger.breakdown().isEmpty());
    }

    @Test
    void everyRecordedFailureCountsTowardTheAggregate() {
        TraversalFailureLedger ledger = new TraversalFailureLedger();

        ledger.record(Failure.HANDOFF_PEER_UNKNOWN, UUID.randomUUID(), "no such peer");
        ledger.record(Failure.HANDOFF_PEER_UNKNOWN, UUID.randomUUID(), null);
        ledger.record(Failure.ENTITY_ACK_DENIED, UUID.randomUUID(), "");

        assertEquals(3L, ledger.failed());
        assertEquals(Long.valueOf(2L), ledger.breakdown().get(Failure.HANDOFF_PEER_UNKNOWN.name()));
        assertEquals(Long.valueOf(1L), ledger.breakdown().get(Failure.ENTITY_ACK_DENIED.name()));
    }

    @Test
    void reasonsThatWereNeverHitAreOmittedFromTheBreakdown() {
        TraversalFailureLedger ledger = new TraversalFailureLedger();

        ledger.record(Failure.ARRIVAL_EXHAUSTED, UUID.randomUUID(), "gave up");

        Map<String, Long> breakdown = ledger.breakdown();
        assertEquals(1, breakdown.size());
        assertEquals(Long.valueOf(1L), breakdown.get(Failure.ARRIVAL_EXHAUSTED.name()));
    }

    @Test
    void theBreakdownFollowsDeclarationOrderNotInsertionOrder() {
        TraversalFailureLedger ledger = new TraversalFailureLedger();

        ledger.record(Failure.ARRIVAL_DENIED_STRANDED, UUID.randomUUID(), "stranded");
        ledger.record(Failure.HANDOFF_RATE_LIMITED, UUID.randomUUID(), "slow down");
        ledger.record(Failure.ENTITY_TIMED_OUT, UUID.randomUUID(), "no ack");

        List<String> keys = new ArrayList<>(ledger.breakdown().keySet());
        assertEquals(List.of(
            Failure.HANDOFF_RATE_LIMITED.name(),
            Failure.ENTITY_TIMED_OUT.name(),
            Failure.ARRIVAL_DENIED_STRANDED.name()
        ), keys);
    }

    @Test
    void theBreakdownIsASnapshotCallersCannotMutate() {
        TraversalFailureLedger ledger = new TraversalFailureLedger();
        ledger.record(Failure.HANDOFF_DENIED, UUID.randomUUID(), "nope");
        Map<String, Long> snapshot = ledger.breakdown();

        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("x", Long.valueOf(1L)));

        ledger.record(Failure.HANDOFF_DENIED, UUID.randomUUID(), "nope again");
        assertEquals(Long.valueOf(1L), snapshot.get(Failure.HANDOFF_DENIED.name()));
        assertEquals(Long.valueOf(2L), ledger.breakdown().get(Failure.HANDOFF_DENIED.name()));
    }

    @Test
    void expectedGameplayOutcomesAreDebugOnly() {
        assertTrue(TraversalFailureLedger.debugOnly(Failure.HANDOFF_RATE_LIMITED));
        assertTrue(TraversalFailureLedger.debugOnly(Failure.HANDOFF_PEER_OFFLINE));
        assertTrue(TraversalFailureLedger.debugOnly(Failure.HANDOFF_DENIED));
        assertTrue(TraversalFailureLedger.debugOnly(Failure.ARRIVAL_DENIED_RETURNED));
    }

    @Test
    void infrastructureFailuresRemainOperatorVisible() {
        assertFalse(TraversalFailureLedger.debugOnly(Failure.HANDOFF_QUEUE_REJECTED));
        assertFalse(TraversalFailureLedger.debugOnly(Failure.ENTITY_TIMED_OUT));
        assertFalse(TraversalFailureLedger.debugOnly(Failure.ARRIVAL_DENIED_STRANDED));
    }
}
