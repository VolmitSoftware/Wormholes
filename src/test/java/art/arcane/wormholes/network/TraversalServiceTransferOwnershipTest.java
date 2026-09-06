package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraversalServiceTransferOwnershipTest {
    @Test
    void delayedArrivalReceiptPreservesTheNewerTransferLock() throws ReflectiveOperationException {
        TraversalService service = new TraversalService(null, (entity, task, retired, delay) -> false);
        UUID playerId = UUID.randomUUID();
        UUID previous = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        long now = System.currentTimeMillis();
        TraversalTransferLocks locks = field(service, "transferLocks", TraversalTransferLocks.class);
        PlayerHandoffCompletion completions = field(service, "handoffCompletions", PlayerHandoffCompletion.class);
        completions.dispatched(new PlayerHandoffCompletion.Attempt(previous, playerId, "beta", now + 60_000L), now);
        locks.lockTransfer(playerId, current, now + 60_000L);

        service.onHandoffResult("beta", new WireMessage.HandoffResult(previous, playerId, true, "arrived"));

        assertTrue(locks.ownsTransfer(playerId, current));
        assertEquals(1L, service.statsSnapshot().completed());
        assertEquals(0, service.statsSnapshot().inFlight());
    }

    @Test
    void expiredPriorReceiptPreservesTheNewerTransferLock() throws ReflectiveOperationException {
        TraversalService service = new TraversalService(null, (entity, task, retired, delay) -> false);
        UUID playerId = UUID.randomUUID();
        UUID previous = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        long now = System.currentTimeMillis();
        TraversalTransferLocks locks = field(service, "transferLocks", TraversalTransferLocks.class);
        PlayerHandoffCompletion completions = field(service, "handoffCompletions", PlayerHandoffCompletion.class);
        completions.dispatched(new PlayerHandoffCompletion.Attempt(previous, playerId, "beta", now - 1L), now - 60_000L);
        locks.lockTransfer(playerId, current, now + 60_000L);

        service.runRecoveryMaintenance();

        assertTrue(locks.ownsTransfer(playerId, current));
        assertEquals(1L, service.statsSnapshot().failed());
        assertEquals(0, service.statsSnapshot().inFlight());
    }

    private static <T> T field(TraversalService service, String name, Class<T> type) throws ReflectiveOperationException {
        Field field = TraversalService.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(service));
    }
}
