package art.arcane.wormholes.proxy.core;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProxyHandoffBrokerTest {
    @Test
    void reservationsAreHeldUntilTheBackendConfirmsThenReleasedOnce() {
        ProxyHandoffBroker broker = new ProxyHandoffBroker();
        UUID transferId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        broker.reserve(transferId, playerId, "lobby-1", "lobby-2", 1_000L);
        assertEquals(1, broker.pending());

        ProxyHandoffBroker.Reservation reservation = broker.complete(transferId);
        assertEquals(playerId, reservation.playerId());
        assertEquals("lobby-1", reservation.sourceServer());
        assertEquals("lobby-2", reservation.targetServer());
        assertNull(broker.complete(transferId));
        assertEquals(0, broker.pending());
    }

    @Test
    void expiredReservationsAreDroppedWithTheirIds() {
        ProxyHandoffBroker broker = new ProxyHandoffBroker();
        UUID stale = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        broker.reserve(stale, UUID.randomUUID(), "a", "b", 0L);
        broker.reserve(fresh, UUID.randomUUID(), "a", "b", 20_000L);
        assertEquals(1, broker.expire(30_001L, 30_000L).size());
        assertNull(broker.complete(stale));
        assertEquals(1, broker.pending());
    }
}
