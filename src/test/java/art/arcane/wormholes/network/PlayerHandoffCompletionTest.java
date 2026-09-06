package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerHandoffCompletionTest {
    @Test
    void lostReceiptIsQueriedUntilTheDestinationConfirmsOnce() {
        PlayerHandoffCompletion ledger = new PlayerHandoffCompletion();
        PlayerHandoffCompletion.Attempt attempt = attempt();
        ledger.dispatched(attempt, 0L);
        assertTrue(ledger.maintain(999L).queries().isEmpty());
        assertEquals(attempt, ledger.maintain(1_000L).queries().getFirst());
        assertTrue(ledger.maintain(1_001L).queries().isEmpty());
        WireMessage.HandoffResult result = result(attempt, true);

        assertNull(ledger.acknowledge("another-server", result));
        assertNull(ledger.acknowledge("beta", new WireMessage.HandoffResult(
            attempt.transferId(), UUID.randomUUID(), true, "arrived")));
        assertEquals(1, ledger.inFlight());
        assertEquals(attempt, ledger.acknowledge("beta", result));
        assertNull(ledger.acknowledge("beta", result));
        assertEquals(0, ledger.inFlight());
        assertTrue(ledger.maintain(60_000L).expired().isEmpty());
    }

    @Test
    void unconfirmedDispatchExpiresOnceAndIsNeverCountedAsSuccessful() {
        PlayerHandoffCompletion ledger = new PlayerHandoffCompletion();
        PlayerHandoffCompletion.Attempt attempt = attempt();
        ledger.dispatched(attempt, 0L);

        assertEquals(attempt, ledger.maintain(60_000L).expired().getFirst());
        assertTrue(ledger.maintain(60_001L).expired().isEmpty());
        assertNull(ledger.acknowledge("beta", result(attempt, true)));
        assertEquals(0, ledger.inFlight());
    }

    @Test
    void receiptsAreBoundToPlayerAndPeerAndKeepTheFirstTerminalOutcome() {
        PlayerHandoffCompletion ledger = new PlayerHandoffCompletion();
        PlayerHandoffCompletion.Attempt attempt = attempt();
        WireMessage.HandoffResult arrived = result(attempt, true);
        WireMessage.HandoffStatus query = new WireMessage.HandoffStatus(attempt.transferId(), attempt.playerId());
        ledger.record("alpha", arrived, 0L);
        ledger.record("alpha", result(attempt, false), 1L);

        assertNull(ledger.receipt("other", query, 2L));
        assertNull(ledger.receipt("alpha", new WireMessage.HandoffStatus(attempt.transferId(), UUID.randomUUID()), 2L));
        assertEquals(arrived, ledger.receipt("alpha", query, 119_999L));
        assertNull(ledger.receipt("alpha", query, 120_000L));
    }

    @Test
    void rejectedDispatchAndShutdownDoNotLeavePendingQueries() {
        PlayerHandoffCompletion ledger = new PlayerHandoffCompletion();
        PlayerHandoffCompletion.Attempt attempt = attempt();
        ledger.dispatched(attempt, 0L);
        ledger.abandon(attempt.transferId());
        assertTrue(ledger.maintain(1_000L).queries().isEmpty());
        ledger.dispatched(attempt, 1_000L);
        ledger.record("alpha", result(attempt, false), 1_000L);
        ledger.clear();
        assertTrue(ledger.maintain(2_000L).queries().isEmpty());
        assertNull(ledger.receipt("alpha", new WireMessage.HandoffStatus(attempt.transferId(), attempt.playerId()), 2_000L));
    }

    private static PlayerHandoffCompletion.Attempt attempt() {
        return new PlayerHandoffCompletion.Attempt(UUID.randomUUID(), UUID.randomUUID(), "beta", 60_000L);
    }

    private static WireMessage.HandoffResult result(PlayerHandoffCompletion.Attempt attempt, boolean arrived) {
        return new WireMessage.HandoffResult(attempt.transferId(), attempt.playerId(), arrived, "test outcome");
    }
}
