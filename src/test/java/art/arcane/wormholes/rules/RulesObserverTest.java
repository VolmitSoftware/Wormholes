package art.arcane.wormholes.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.hook.TraversalAttempt;
import art.arcane.wormholes.hook.TraversalPhase;
import art.arcane.wormholes.hook.TraversalVerdict;
import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.localization.RulesMessages;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.LocalTunnel;
import art.arcane.wormholes.portal.PortalType;

final class RulesObserverTest {
    private RuleTraversalLedger ledger;
    private RulesGate gate;
    private RulesObserver observer;
    private World world;
    private LocalPortal portal;
    private LocalPortal destination;
    private RulesTestSupport.FakeTraveler traveler;

    @BeforeEach
    void install() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(new RulesExtensionFactory()));
        PortalCooldowns.clear();
        ledger = new RuleTraversalLedger();
        gate = new RulesGate(new FakeRulesEnvironment(), new WarmupTracker(new SilentPinner()), ledger);
        observer = new RulesObserver(ledger);
        world = RulesTestSupport.world("observer");
        portal = RulesTestSupport.portal(world, PortalType.PORTAL);
        destination = RulesTestSupport.portal(world, PortalType.PORTAL);
        traveler = RulesTestSupport.FakeTraveler.player("traveler", origin(), Set.of());
        traveler.level(30);
    }

    @AfterEach
    void clearHooks() {
        WormholesHooks.clear();
        PortalCooldowns.clear();
    }

    @Test
    void departingCommitsTheReservationAndStartsTheCooldown() {
        document(new RuleDocument(List.of(new Rule("toll", List.of(), RuleOutcome.allow(),
            List.of(new Cost.Xp(5, true)), List.of())),
            RuleOutcome.allow(), new TraversalProfile(5000L, "", 0L, 1.0D, 1.0D, 0, 0), 0L));
        gate.evaluate(attempt(TraversalPhase.DEPART, 1000L));
        assertEquals(30, traveler.level());

        observer.onDeparted(attempt(TraversalPhase.DEPART, 1000L));

        assertEquals(25, traveler.level());
        assertEquals(5000L, PortalCooldowns.remainingMillis(traveler.id(), portal.getId(), "", 1000L));
        assertEquals(0, ledger.size());
    }

    @Test
    void aRejectionAfterTheGateLeavesTheTravelerWhole() {
        document(new RuleDocument(List.of(new Rule("toll", List.of(), RuleOutcome.allow(),
            List.of(new Cost.Xp(5, true)), List.of())),
            RuleOutcome.allow(), TraversalProfile.DEFAULT, 0L));
        gate.evaluate(attempt(TraversalPhase.DEPART, 1000L));
        assertEquals(30, traveler.level());

        observer.onRejected(attempt(TraversalPhase.DEPART, 1000L),
            TraversalVerdict.Deny.of(RulesMessages.DENIED_DEFAULT));

        assertEquals(30, traveler.level());
        assertEquals(0, ledger.size());
    }

    @Test
    void aDeliveryFailureAfterTheDepartureRefundsTheCostsAndReleasesTheCooldown() {
        document(new RuleDocument(List.of(new Rule("toll", List.of(), RuleOutcome.allow(),
            List.of(new Cost.Xp(5, true)), List.of())),
            RuleOutcome.allow(), new TraversalProfile(5000L, "", 0L, 1.0D, 1.0D, 0, 0), 0L));
        gate.evaluate(localDeparture(1000L));
        observer.onDeparted(localDeparture(1000L));
        assertEquals(25, traveler.level());
        assertTrue(PortalCooldowns.remainingMillis(traveler.id(), portal.getId(), "", 1000L) > 0L);

        observer.onDeliveryFailed(portal, traveler.entity());

        assertEquals(30, traveler.level());
        assertEquals(0L, PortalCooldowns.remainingMillis(traveler.id(), portal.getId(), "", 1000L));
        assertEquals(0, ledger.size());
    }

    @Test
    void aLocalArrivalCommitsWhatTheDepartureTook() {
        document(new RuleDocument(List.of(new Rule("toll", List.of(), RuleOutcome.allow(),
            List.of(new Cost.Xp(5, true)), List.of())),
            RuleOutcome.allow(), TraversalProfile.DEFAULT, 0L));
        gate.evaluate(localDeparture(1000L));
        observer.onDeparted(localDeparture(1000L));

        observer.onArrived(destination, traveler.entity(), origin());
        observer.onDeliveryFailed(portal, traveler.entity());

        assertEquals(25, traveler.level());
        assertEquals(0, ledger.size());
    }

    @Test
    void aGatewayDepartureCommitsAtTheDepartureBecauseNoLocalArrivalFollows() {
        document(new RuleDocument(List.of(new Rule("toll", List.of(), RuleOutcome.allow(),
            List.of(new Cost.Xp(5, true)), List.of())),
            RuleOutcome.allow(), TraversalProfile.DEFAULT, 0L));
        gate.evaluate(attempt(TraversalPhase.DEPART, 1000L));
        observer.onDeparted(attempt(TraversalPhase.DEPART, 1000L));

        ledger.prune(1000L + RuleTraversalLedger.TTL_MILLIS + 1L);

        assertEquals(25, traveler.level());
        assertEquals(0, ledger.size());
    }

    @Test
    void arrivalRunsTheMatchedRuleEffectsOnce() {
        document(new RuleDocument(List.of(new Rule("boost", List.of(), RuleOutcome.allow(), List.of(),
            List.of(new Effect.Velocity(1.5D)))),
            RuleOutcome.allow(), TraversalProfile.DEFAULT, 0L));
        gate.evaluate(attempt(TraversalPhase.DEPART, 1000L));
        observer.onDeparted(attempt(TraversalPhase.DEPART, 1000L));

        observer.onArrived(portal, traveler.entity(), origin());
        observer.onArrived(portal, traveler.entity(), origin());

        assertEquals(1, traveler.velocities().size());
        assertEquals(1.5D, traveler.velocities().getFirst().getZ());
    }

    @Test
    void anAbandonedEvaluationAgesOutWithNothingTaken() {
        document(new RuleDocument(List.of(new Rule("toll", List.of(), RuleOutcome.allow(),
            List.of(new Cost.Xp(5, true)), List.of())),
            RuleOutcome.allow(), TraversalProfile.DEFAULT, 0L));
        gate.evaluate(attempt(TraversalPhase.DEPART, 1000L));
        assertEquals(30, traveler.level());

        ledger.prune(1000L + RuleTraversalLedger.TTL_MILLIS + 1L);

        assertEquals(30, traveler.level());
        assertEquals(0, ledger.size());
    }

    @Test
    void crossingTwiceWithoutDepartingNeverDoubleCharges() {
        document(new RuleDocument(List.of(new Rule("toll", List.of(), RuleOutcome.allow(),
            List.of(new Cost.Xp(5, true)), List.of())),
            RuleOutcome.allow(), TraversalProfile.DEFAULT, 0L));

        assertSame(TraversalVerdict.ALLOW, gate.evaluate(attempt(TraversalPhase.DEPART, 1000L)));
        assertSame(TraversalVerdict.ALLOW, gate.evaluate(attempt(TraversalPhase.DEPART, 1001L)));

        assertEquals(30, traveler.level());
        assertEquals(1, ledger.size());
        assertTrue(PortalCooldowns.remainingMillis(traveler.id(), portal.getId(), "", 1001L) == 0L);
    }

    /**
     * The rider case from the headline review: a traveler holding exactly the cost is deferred by a later
     * gate every tick. Evaluating the gate must never take anything, so the traveler is still admitted on
     * the second tick and pays exactly once, when the departure settles.
     */
    @Test
    void aDeferredRiderIsChargedOnceAndStillAdmittedOnTheSecondTick() {
        traveler.level(5);
        document(new RuleDocument(List.of(new Rule("toll", List.of(), RuleOutcome.allow(),
            List.of(new Cost.Xp(5, true)), List.of())),
            RuleOutcome.allow(), TraversalProfile.DEFAULT, 0L));

        assertSame(TraversalVerdict.ALLOW, gate.evaluate(attempt(TraversalPhase.DEPART, 1000L)));
        assertEquals(5, traveler.level());
        assertSame(TraversalVerdict.ALLOW, gate.evaluate(attempt(TraversalPhase.DEPART, 1050L)));
        assertEquals(5, traveler.level());
        assertSame(TraversalVerdict.ALLOW, gate.evaluate(attempt(TraversalPhase.DEPART, 1100L)));
        assertEquals(5, traveler.level());

        observer.onDeparted(attempt(TraversalPhase.DEPART, 1150L));

        assertEquals(0, traveler.level());
        assertEquals(0, ledger.size());
    }

    @Test
    void aTravelerWhoCannotPayIsRefusedWithoutTakingAnything() {
        traveler.level(4);
        document(new RuleDocument(List.of(new Rule("toll", List.of(), RuleOutcome.allow(),
            List.of(new Cost.Xp(5, true)), List.of())),
            RuleOutcome.allow(), TraversalProfile.DEFAULT, 0L));

        TraversalVerdict verdict = gate.evaluate(attempt(TraversalPhase.DEPART, 1000L));

        assertInstanceOf(TraversalVerdict.Deny.class, verdict);
        assertEquals(RulesMessages.DENIED_COST, ((TraversalVerdict.Deny) verdict).reason());
        assertEquals(4, traveler.level());
        assertEquals(0, ledger.size());
    }

    /**
     * A rejection at some other portal must not eat what this portal's departure is carrying.
     */
    @Test
    void aRejectionAtAnotherPortalLeavesThisPortalsPendingCrossingAlone() {
        document(new RuleDocument(List.of(new Rule("boost", List.of(), RuleOutcome.allow(), List.of(),
            List.of(new Effect.Velocity(1.5D)))),
            RuleOutcome.allow(), TraversalProfile.DEFAULT, 0L));
        gate.evaluate(localDeparture(1000L));
        observer.onDeparted(localDeparture(1000L));

        observer.onRejected(new TraversalAttempt(TraversalPhase.DEPART, destination, traveler.entity(), null, null, 1100L),
            TraversalVerdict.Deny.of(RulesMessages.DENIED_DEFAULT));

        assertEquals(1, ledger.size());

        observer.onArrived(destination, traveler.entity(), origin());

        assertEquals(1, traveler.velocities().size());
    }

    private void document(RuleDocument replacement) {
        portal.extension(RulesPortalExtension.class).setDocument(replacement);
    }

    private TraversalAttempt attempt(TraversalPhase phase, long nowMillis) {
        return new TraversalAttempt(phase, portal, traveler.entity(), null, null, nowMillis);
    }

    /** A departure that ends in a local arrival, which is the only shape that defers the commit. */
    private TraversalAttempt localDeparture(long nowMillis) {
        return new TraversalAttempt(TraversalPhase.DEPART, portal, traveler.entity(),
            new LocalTunnel(destination), null, nowMillis);
    }

    private Location origin() {
        return new Location(world, 0.0D, 64.0D, 1.0D);
    }

    private static final class SilentPinner implements WarmupTracker.Pinner {
        @Override
        public void schedule(java.util.UUID playerId) {
        }

        @Override
        public void pin(java.util.UUID playerId, long secondsLeft) {
        }

        @Override
        public void cancelled(java.util.UUID playerId) {
        }
    }
}
