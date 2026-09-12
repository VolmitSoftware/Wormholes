package art.arcane.wormholes.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class RuleCostReservationTest {
    private World world;
    private RulesTestSupport.FakeTraveler traveler;

    @BeforeEach
    void freshTraveler() {
        world = RulesTestSupport.world("costs");
        traveler = RulesTestSupport.FakeTraveler.player("payer", new Location(world, 0.0D, 64.0D, 0.0D), Set.of());
        traveler.level(30);
        traveler.foodLevel(20);
        traveler.health(20.0D);
    }

    @Test
    void experienceLevelsLeaveOnReserveAndComeBackOnRefund() {
        RuleCostReservation reservation = RuleCostReservation.reserve(traveler.player(), List.of(new Cost.Xp(5, true)), pool(0));

        assertTrue(reservation.successful());
        assertEquals(25, traveler.level());

        reservation.refund();
        assertEquals(30, traveler.level());
    }

    @Test
    void committingKeepsWhatTheReservationTook() {
        RuleCostReservation reservation = RuleCostReservation.reserve(traveler.player(),
            List.of(new Cost.Xp(5, true), new Cost.Hunger(4)), pool(0));

        reservation.commit();

        assertEquals(25, traveler.level());
        assertEquals(16, traveler.foodLevel());
    }

    @Test
    void hungerAndHealthComeBackOnRefund() {
        RuleCostReservation reservation = RuleCostReservation.reserve(traveler.player(),
            List.of(new Cost.Hunger(6), new Cost.Health(4.0D)), pool(0));

        assertTrue(reservation.successful());
        assertEquals(14, traveler.foodLevel());
        assertEquals(16.0D, traveler.health());

        reservation.refund();
        assertEquals(20, traveler.foodLevel());
        assertEquals(20.0D, traveler.health());
    }

    /**
     * The health-refund case from the headline review: the refund capped at the vanilla twenty, so any
     * traveler whose maximum is higher lost the difference every time a crossing was refunded.
     */
    @Test
    void aHealthRefundStopsAtTheTravelersOwnMaximumNotTwenty() {
        traveler.maximumHealth(40.0D);
        traveler.health(30.0D);

        RuleCostReservation reservation = RuleCostReservation.reserve(traveler.player(),
            List.of(new Cost.Health(4.0D)), pool(0));

        assertTrue(reservation.successful());
        assertEquals(26.0D, traveler.health());

        reservation.refund();
        assertEquals(30.0D, traveler.health());
    }

    @Test
    void aChargeCostDecrementsThePoolOnlyOnCommit() {
        ChargePool charges = pool(4);
        RuleCostReservation reservation = RuleCostReservation.reserve(traveler.player(), List.of(new Cost.Charge(2)), charges);

        assertTrue(reservation.successful());
        assertEquals(4, charges.count());

        reservation.commit();
        assertEquals(2, charges.count());
    }

    @Test
    void anUnaffordableCostRollsBackEverythingAlreadyTakenAndNamesTheCost() {
        ChargePool charges = pool(1);
        RuleCostReservation reservation = RuleCostReservation.reserve(traveler.player(),
            List.of(new Cost.Hunger(4), new Cost.Charge(9)), charges);

        assertFalse(reservation.successful());
        assertInstanceOf(Cost.Charge.class, reservation.failedCost());
        assertEquals(20, traveler.foodLevel());
        assertEquals(1, charges.count());
    }

    @Test
    void healthNeverDropsBelowHalfAHeart() {
        traveler.health(3.0D);

        RuleCostReservation reservation = RuleCostReservation.reserve(traveler.player(), List.of(new Cost.Health(4.0D)), pool(0));

        assertFalse(reservation.successful());
        assertEquals(3.0D, traveler.health());
    }

    @Test
    void vaultCostsFailClosedWhenNoEconomyIsInstalled() {
        RuleCostReservation reservation = RuleCostReservation.reserve(traveler.player(),
            List.of(new Cost.Vault(new BigDecimal("10.00"))), pool(0));

        assertFalse(reservation.successful());
        assertInstanceOf(Cost.Vault.class, reservation.failedCost());
    }

    @Test
    void anEmptyCostListReservesTrivially() {
        RuleCostReservation reservation = RuleCostReservation.reserve(traveler.player(), List.of(), pool(0));

        assertTrue(reservation.successful());
        reservation.commit();
        reservation.refund();
        assertEquals(30, traveler.level());
    }

    @Test
    void chargesRegenerateOnTheirOwnCadenceWithoutATimer() {
        ChargePool charges = new ChargePool();
        charges.reshape(new TraversalProfile(0L, "", 0L, 1.0D, 1.0D, 10, 2), 0L);
        assertTrue(charges.consume(8, 0L));
        assertEquals(2, charges.count());

        assertFalse(charges.canConsume(4, 59_000L, 60_000L));
        assertTrue(charges.canConsume(4, 120_000L, 60_000L));
        assertEquals(6, charges.count());

        charges.regen(10_000_000L, 60_000L);
        assertEquals(10, charges.count());
    }

    private static ChargePool pool(int capacity) {
        ChargePool charges = new ChargePool();
        charges.reshape(new TraversalProfile(0L, "", 0L, 1.0D, 1.0D, capacity, 0), 0L);
        return charges;
    }
}
