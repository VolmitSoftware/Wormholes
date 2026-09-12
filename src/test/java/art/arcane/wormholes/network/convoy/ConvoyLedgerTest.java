package art.arcane.wormholes.network.convoy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

final class ConvoyLedgerTest {
    @Test
    void aGroupIsAdmittedOnlyWhenEveryMemberIsAdmitted() {
        ConvoyLedger ledger = new ConvoyLedger();
        UUID groupId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID boat = UUID.randomUUID();
        UUID horse = UUID.randomUUID();

        ConvoyLedger.Group opened = ledger.open(groupId, playerId, "beta", List.of(boat, playerId, horse), 100L);
        assertEquals(ConvoyLedger.Phase.OPEN, opened.phase());
        assertNull(ledger.open(groupId, playerId, "beta", List.of(boat), 101L), "duplicate group ids are refused");

        assertEquals(ConvoyLedger.Phase.OPEN, ledger.admitMember(groupId, boat).phase());
        assertEquals(ConvoyLedger.Phase.OPEN, ledger.admitMember(groupId, playerId).phase());
        assertEquals(ConvoyLedger.Phase.OPEN, ledger.admitMember(groupId, boat).phase(), "re-admitting a member does not count twice");
        assertEquals(ConvoyLedger.Phase.ADMITTED, ledger.admitMember(groupId, horse).phase());
        assertEquals(ConvoyLedger.Phase.DISPATCHED, ledger.dispatch(groupId).phase());
        assertEquals(ConvoyLedger.Phase.COMPLETED, ledger.complete(groupId).phase());
        assertNull(ledger.fail(groupId, "late"), "terminal groups never change again");
        assertNull(ledger.find(groupId), "find only reports live groups");
    }

    @Test
    void oneRefusedMemberFailsTheWholeGroup() {
        ConvoyLedger ledger = new ConvoyLedger();
        UUID groupId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID boat = UUID.randomUUID();
        UUID horse = UUID.randomUUID();
        ledger.open(groupId, playerId, "beta", List.of(boat, playerId, horse), 100L);
        ledger.admitMember(groupId, boat);

        ConvoyLedger.Group failed = ledger.fail(groupId, "horse refused");

        assertEquals(ConvoyLedger.Phase.FAILED, failed.phase());
        assertEquals("horse refused", failed.reason());
        assertNull(ledger.admitMember(groupId, horse));
        assertNull(ledger.admitMember(groupId, playerId));
        assertNull(ledger.findByPlayer(playerId));
        assertTrue(ledger.inFlight().isEmpty());
    }

    @Test
    void expiryFailsLiveGroupsPastTheirTtlExactlyOnceAndPruneForgetsTerminalOnes() {
        ConvoyLedger ledger = new ConvoyLedger();
        UUID stale = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        UUID stalePlayer = UUID.randomUUID();
        ledger.open(stale, stalePlayer, "beta", List.of(stalePlayer), 1_000L);
        ledger.open(fresh, UUID.randomUUID(), "beta", List.of(UUID.randomUUID()), 15_000L);
        assertNotNull(ledger.findByPlayer(stalePlayer));

        List<ConvoyLedger.Group> expired = ledger.expire(21_500L, 20_000L);

        assertEquals(1, expired.size());
        assertEquals(stale, expired.getFirst().groupId());
        assertEquals(ConvoyLedger.Phase.FAILED, expired.getFirst().phase());
        assertTrue(ledger.expire(21_600L, 20_000L).isEmpty(), "an expired group is reported once");
        assertEquals(1, ledger.inFlight().size());
        assertEquals(fresh, ledger.inFlight().getFirst().groupId());
        assertNull(ledger.findByPlayer(stalePlayer));

        ledger.prune(200_000L, 60_000L);
        assertNull(ledger.find(stale));
        assertNotNull(ledger.find(fresh));
    }
}
