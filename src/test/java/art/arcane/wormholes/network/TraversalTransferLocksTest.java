package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraversalTransferLocksTest {
    @Test
    void anUnknownTravelerIsNeverLocked() {
        TraversalTransferLocks locks = new TraversalTransferLocks();

        assertEquals(0L, locks.remaining(UUID.randomUUID(), 1_000L));
        assertFalse(locks.isLocked(UUID.randomUUID(), 1_000L));
    }

    @Test
    void remainingCountsDownTowardTheDeadline() {
        TraversalTransferLocks locks = new TraversalTransferLocks();
        UUID traveler = UUID.randomUUID();
        locks.lock(traveler, 5_000L);

        assertEquals(4_000L, locks.remaining(traveler, 1_000L));
        assertEquals(1L, locks.remaining(traveler, 4_999L));
        assertTrue(locks.isLocked(traveler, 4_999L));
    }

    @Test
    void anExpiredLockReadsAsFreeAndIsDroppedOnRead() {
        TraversalTransferLocks locks = new TraversalTransferLocks();
        UUID traveler = UUID.randomUUID();
        locks.lock(traveler, 5_000L);

        assertEquals(0L, locks.remaining(traveler, 5_000L));
        assertFalse(locks.isLocked(traveler, 5_000L));
        assertEquals(0L, locks.remaining(traveler, 1L));
    }

    @Test
    void relockingReplacesTheDeadlineRatherThanExtendingIt() {
        TraversalTransferLocks locks = new TraversalTransferLocks();
        UUID traveler = UUID.randomUUID();
        locks.lock(traveler, 9_000L);
        locks.lock(traveler, 3_000L);

        assertEquals(2_000L, locks.remaining(traveler, 1_000L));
    }

    @Test
    void unlockingClearsTheClaimImmediately() {
        TraversalTransferLocks locks = new TraversalTransferLocks();
        UUID traveler = UUID.randomUUID();
        locks.lock(traveler, 5_000L);
        locks.unlock(traveler);

        assertFalse(locks.isLocked(traveler, 1_000L));
    }

    @Test
    void aPriorTransferCannotReleaseOrRenewANewerLeaseEvenWithTheSameDeadline() {
        TraversalTransferLocks locks = new TraversalTransferLocks();
        UUID traveler = UUID.randomUUID();
        UUID previous = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        locks.lockTransfer(traveler, previous, 5_000L);
        locks.lockTransfer(traveler, current, 5_000L);

        assertFalse(locks.unlockTransfer(traveler, previous));
        assertFalse(locks.renewTransfer(traveler, previous, 9_000L));
        assertEquals(4_000L, locks.remaining(traveler, 1_000L));
        assertTrue(locks.ownsTransfer(traveler, current));
        assertTrue(locks.renewTransfer(traveler, current, 9_000L));
        assertEquals(8_000L, locks.remaining(traveler, 1_000L));
        assertTrue(locks.unlockTransfer(traveler, current));
        assertFalse(locks.ownsTransfer(traveler, current));
    }

    @Test
    void aTransferReceiptCannotClearAnEntityLease() {
        TraversalTransferLocks locks = new TraversalTransferLocks();
        UUID traveler = UUID.randomUUID();
        UUID previous = UUID.randomUUID();
        locks.lockTransfer(traveler, previous, 5_000L);
        locks.lock(traveler, 5_000L);

        assertFalse(locks.unlockTransfer(traveler, previous));
        assertTrue(locks.isLocked(traveler, 1_000L));
        locks.unlock(traveler);
        assertFalse(locks.isLocked(traveler, 1_000L));
    }

    @Test
    void pruningBelowTheThresholdKeepsExpiredLocksAddressable() {
        TraversalTransferLocks locks = new TraversalTransferLocks();
        UUID traveler = UUID.randomUUID();
        locks.lock(traveler, 5_000L);

        locks.prune(9_000L);

        assertTrue(locks.isLocked(traveler, 1_000L));
        assertEquals(0L, locks.remaining(traveler, 9_000L));
    }

    @Test
    void pruningAtTheThresholdDropsOnlyExpiredLocks() {
        TraversalTransferLocks locks = new TraversalTransferLocks();
        UUID live = UUID.randomUUID();
        for (int index = 0; index < 511; index++) {
            locks.lock(UUID.randomUUID(), 1_000L);
        }
        locks.lock(live, 9_000L);

        locks.prune(5_000L);

        assertEquals(4_000L, locks.remaining(live, 5_000L));
    }
}
