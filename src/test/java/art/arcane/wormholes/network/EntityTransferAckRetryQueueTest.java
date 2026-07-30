package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EntityTransferAckRetryQueueTest {
    private static final String PEER = "beta";
    private static final long TTL_MILLIS = 60_000L;

    @Test
    void acceptedAckRetriesAtTheMaintenanceIntervalUntilItsTtlExpires() {
        EntityTransferAckRetryQueue queue = new EntityTransferAckRetryQueue();
        UUID transferId = UUID.randomUUID();
        WireMessage.EntityTransferAck ack = new WireMessage.EntityTransferAck(transferId, true);

        queue.track(PEER, ack, 0L, TTL_MILLIS);

        assertTrue(queue.due(EntityTransferAckRetryQueue.RETRY_INTERVAL_MILLIS - 1L).isEmpty());
        assertEquals(List.of(new EntityTransferAckRetryQueue.Retry(PEER, ack)),
            queue.due(EntityTransferAckRetryQueue.RETRY_INTERVAL_MILLIS));
        assertTrue(queue.due(EntityTransferAckRetryQueue.RETRY_INTERVAL_MILLIS).isEmpty());
        assertEquals(List.of(new EntityTransferAckRetryQueue.Retry(PEER, ack)),
            queue.due(EntityTransferAckRetryQueue.RETRY_INTERVAL_MILLIS * 2L));
        assertTrue(queue.due(TTL_MILLIS).isEmpty());
    }

    @Test
    void aRejectedInitialQueueAttemptIsEligibleForImmediateMaintenanceRetry() {
        EntityTransferAckRetryQueue queue = new EntityTransferAckRetryQueue();
        UUID transferId = UUID.randomUUID();
        WireMessage.EntityTransferAck ack = new WireMessage.EntityTransferAck(transferId, true);

        queue.track(PEER, ack, 1_000L, TTL_MILLIS);
        queue.expedite(transferId, 1_000L);

        assertEquals(List.of(new EntityTransferAckRetryQueue.Retry(PEER, ack)), queue.due(1_000L));
    }

    @Test
    void duplicateTrackingDoesNotExtendTheOriginalRecoveryWindow() {
        EntityTransferAckRetryQueue queue = new EntityTransferAckRetryQueue();
        UUID transferId = UUID.randomUUID();
        WireMessage.EntityTransferAck ack = new WireMessage.EntityTransferAck(transferId, true);

        queue.track(PEER, ack, 0L, TTL_MILLIS);
        queue.track(PEER, ack, TTL_MILLIS - 1L, TTL_MILLIS);

        assertTrue(queue.due(TTL_MILLIS).isEmpty());
    }

    @Test
    void pendingRetriesAreBoundedAndEvictTheOldestTransfer() {
        EntityTransferAckRetryQueue queue = new EntityTransferAckRetryQueue();
        UUID oldestTransferId = UUID.randomUUID();
        queue.track(PEER, new WireMessage.EntityTransferAck(oldestTransferId, true), 0L, TTL_MILLIS);

        UUID newestTransferId = null;
        for (int i = 1; i <= EntityTransferAckRetryQueue.MAX_PENDING; i++) {
            newestTransferId = UUID.randomUUID();
            queue.track(PEER, new WireMessage.EntityTransferAck(newestTransferId, true), 0L, TTL_MILLIS);
        }

        List<EntityTransferAckRetryQueue.Retry> retries =
            queue.due(EntityTransferAckRetryQueue.RETRY_INTERVAL_MILLIS);
        assertEquals(EntityTransferAckRetryQueue.MAX_PENDING, retries.size());
        assertFalse(contains(retries, oldestTransferId));
        assertTrue(contains(retries, newestTransferId));
    }

    private static boolean contains(List<EntityTransferAckRetryQueue.Retry> retries, UUID transferId) {
        for (EntityTransferAckRetryQueue.Retry retry : retries) {
            if (retry.ack().transferId().equals(transferId)) {
                return true;
            }
        }
        return false;
    }
}
