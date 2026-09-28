package art.arcane.wormholes.rules;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class RuleCostPolicyTest {
    @Test
    void laterFailureRefundsEarlierCurrencyAndExperienceExactlyOnce() {
        RuleCostReservation.Subject<String> subject = subject();
        AtomicInteger level = new AtomicInteger(10);
        when(subject.level()).thenAnswer(invocation -> level.get());
        doAnswer(invocation -> { level.set(invocation.getArgument(0)); return null; }).when(subject).setLevel(anyInt());
        RuleCostReservation.CurrencyCharge charge = mock(RuleCostReservation.CurrencyCharge.class);
        when(subject.withdrawCurrency(BigDecimal.ONE)).thenReturn(charge);
        when(subject.food()).thenReturn(0);

        RuleCostReservation reservation = RuleCostReservation.reserve(subject,
            List.of(new Cost.Xp(3, true), new Cost.Vault(BigDecimal.ONE), new Cost.Hunger(1)), pool(0));

        assertFalse(reservation.successful());
        assertEquals(new Cost.Hunger(1), reservation.failedCost());
        assertEquals(10, level.get());
        reservation.refund();
        reservation.commit();
        verify(charge).refund();
    }

    @Test
    void failedPartialItemCollectionReturnsRemovedStacks() {
        RuleCostReservation.Subject<String> subject = subject();
        ItemMatcher matcher = ItemMatcher.material("DIAMOND");
        when(subject.inventory()).thenReturn(List.of("first", "second"));
        when(subject.matches("first", matcher)).thenReturn(true);
        when(subject.count("first")).thenReturn(2);
        when(subject.copy("first", 2)).thenReturn("removed");

        RuleCostReservation reservation = RuleCostReservation.reserve(subject,
            List.of(new Cost.Item(matcher, 3)), pool(0));

        assertFalse(reservation.successful());
        verify(subject).setCount("first", 0);
        verify(subject).returnItems(List.of("removed"));
    }

    @Test
    void chargeCommitAndRegenerationSurviveSavedState() {
        ChargePool charges = pool(5);
        RuleCostReservation reservation = RuleCostReservation.reserve(subject(), List.of(new Cost.Charge(2)), charges);
        assertEquals(5, charges.count());
        reservation.commit();
        reservation.commit();
        reservation.refund();
        assertEquals(3, charges.count());
        ChargePool restored = pool(5);
        restored.load(charges.save());
        assertEquals(3, restored.count());
        assertTrue(restored.consume(3, System.currentTimeMillis()));
        assertFalse(restored.consume(1, System.currentTimeMillis()));
    }

    @Test
    void experienceUsesLevelCurveAndProgressAtBoundaries() {
        RuleCostReservation.Subject<String> subject = subject();
        when(subject.experienceProgress()).thenReturn(0.5F);
        when(subject.level()).thenReturn(16);
        assertEquals(373, RuleCostReservation.experiencePoints(subject));
        when(subject.level()).thenReturn(31);
        assertEquals(1568, RuleCostReservation.experiencePoints(subject));
        when(subject.level()).thenReturn(32);
        assertEquals(1693, RuleCostReservation.experiencePoints(subject));
    }

    @Test
    void exhaustedTicketIsRestoredAndUnlimitedTicketIsUnchanged() {
        UUID identity = UUID.randomUUID();
        TicketHost host = new TicketHost(identity, 2);
        assertFalse(KeyItemUses.spend(host, host.ticket, 3));
        assertTrue(KeyItemUses.spend(host, host.ticket, 2));
        assertTrue(host.ticket.isEmpty());
        KeyItemUses.refund(host, identity, 2);
        assertEquals(List.of(2), host.ticket);
        host.ticket.set(0, 0);
        assertTrue(KeyItemUses.spend(host, host.ticket, 20));
        KeyItemUses.refund(host, identity, 20);
        assertEquals(List.of(0), host.ticket);
    }

    @SuppressWarnings("unchecked")
    private static RuleCostReservation.Subject<String> subject() {
        return mock(RuleCostReservation.Subject.class);
    }

    private static ChargePool pool(int count) {
        ChargePool pool = new ChargePool(() -> 60_000L);
        pool.reshape(new TraversalProfile(0L, "", 0L, 1.0D, 1.0D, count, 0), 1L);
        return pool;
    }

    private record TicketHost(UUID identity, List<Integer> ticket) implements KeyItemUses.Host<List<Integer>> {
        private TicketHost(UUID identity, int uses) {
            this(identity, new ArrayList<>(List.of(uses)));
        }

        @Override
        public List<Integer> find(UUID requested) {
            return identity.equals(requested) && !ticket.isEmpty() ? ticket : null;
        }

        @Override
        public int remaining(List<Integer> stack) {
            return stack.getFirst();
        }

        @Override
        public void setRemaining(List<Integer> stack, int remaining) {
            stack.set(0, remaining);
        }

        @Override
        public void remove(List<Integer> stack) {
            stack.clear();
        }

        @Override
        public void give(UUID requested, int uses) {
            ticket.add(uses);
        }
    }
}
