package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityTransferLedgerTest {
    @Test
    void duplicateOnlyAcknowledgesAfterSuccessfulApplication() {
        TraversalEntityTransferLedger ledger = new TraversalEntityTransferLedger();
        UUID transferId = UUID.randomUUID();

        TraversalEntityTransferLedger.Claim started = ledger.claim(transferId, 100L);
        TraversalEntityTransferLedger.Claim inFlight = ledger.claim(transferId, 101L);

        assertEquals(TraversalEntityTransferLedger.ClaimStatus.STARTED, started.status());
        assertEquals(TraversalEntityTransferLedger.ClaimStatus.IN_FLIGHT, inFlight.status());
        assertTrue(ledger.markApplied(transferId, started, 102L));
        assertEquals(
            TraversalEntityTransferLedger.ClaimStatus.APPLIED,
            ledger.claim(transferId, 103L).status()
        );
    }

    @Test
    void failedApplicationReleasesClaimForSafeRetry() {
        TraversalEntityTransferLedger ledger = new TraversalEntityTransferLedger();
        UUID transferId = UUID.randomUUID();
        TraversalEntityTransferLedger.Claim failed = ledger.claim(transferId, 100L);

        assertTrue(ledger.release(transferId, failed));

        TraversalEntityTransferLedger.Claim retry = ledger.claim(transferId, 101L);
        assertEquals(TraversalEntityTransferLedger.ClaimStatus.STARTED, retry.status());
        assertNotEquals(failed.token(), retry.token());
        assertFalse(ledger.markApplied(transferId, failed, 102L));
        assertTrue(ledger.markApplied(transferId, retry, 103L));
    }

    @Test
    void pruningNeverReleasesAnInFlightTransfer() {
        TraversalEntityTransferLedger ledger = new TraversalEntityTransferLedger();
        UUID inFlightId = UUID.randomUUID();
        UUID appliedId = UUID.randomUUID();
        TraversalEntityTransferLedger.Claim inFlight = ledger.claim(inFlightId, 10L);
        TraversalEntityTransferLedger.Claim applied = ledger.claim(appliedId, 10L);
        assertTrue(ledger.markApplied(appliedId, applied, 20L));

        ledger.pruneApplied(1_000L, 100L, 0);

        assertEquals(TraversalEntityTransferLedger.ClaimStatus.IN_FLIGHT, ledger.claim(inFlightId, 1_001L).status());
        assertEquals(TraversalEntityTransferLedger.ClaimStatus.STARTED, ledger.claim(appliedId, 1_001L).status());
        assertFalse(ledger.release(inFlightId, applied));
        assertTrue(ledger.release(inFlightId, inFlight));
    }
}
