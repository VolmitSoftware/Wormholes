package art.arcane.wormholes.rules;

import art.arcane.wormholes.hook.TraversalPhase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class RuleAdmissionTest {
    @Test
    void warmupStagesCostOnlyWhenCompletedAndChargesRemainUntaken() {
        Fixture fixture = new Fixture(new TraversalProfile(0, "", 500, 1, 1, 3, 0));
        assertEquals(RuleAdmission.DeferredWarmup.INSTANCE, fixture.evaluate(1000, false));
        assertNull(fixture.ledger.peek(fixture.traveler));
        assertEquals(RuleAdmission.DeferredWarmup.INSTANCE, fixture.evaluate(1499, false));
        assertEquals(RuleAdmission.Allow.INSTANCE, fixture.evaluate(1500, false));
        assertEquals(List.of(new Cost.Charge(1)), fixture.ledger.peek(fixture.traveler).costs());
        assertEquals(3, fixture.charges.count());
    }

    @Test
    void movementCancellationKeepsGraceInsteadOfStartingAnotherWarmup() {
        Fixture fixture = new Fixture(new TraversalProfile(0, "", 500, 1, 1, 3, 0));
        fixture.evaluate(1000, false);
        fixture.warmups.cancelOnMove(fixture.traveler, new WarmupTracker.Anchor("world", 2, 0), 0.5, 1100);
        assertEquals(RuleAdmission.CancelledWarmup.INSTANCE, fixture.evaluate(1600, false));
        assertNull(fixture.ledger.peek(fixture.traveler));
    }

    @Test
    void screeningChecksRulesButDoesNotApplyWarmupOrStagePayments() {
        Fixture fixture = new Fixture(new TraversalProfile(1000, "group", 500, 1, 1, 3, 0));
        PortalCooldowns.stamp(fixture.traveler, fixture.portal, "group", 1000, 1000);
        assertEquals(RuleAdmission.Allow.INSTANCE, fixture.evaluate(1100, true));
        assertNull(fixture.ledger.peek(fixture.traveler));
        assertInstanceOf(RuleAdmission.DeniedCooldown.class, fixture.evaluate(1100, false));
    }

    @Test
    void stagedLedgerRetentionDoesNotMarkDepartureOrExpireItEarly() {
        RuleTraversalLedger ledger = new RuleTraversalLedger();
        UUID traveler = UUID.randomUUID();
        RuleTraversalLedger.Pending pending = new RuleTraversalLedger.Pending(UUID.randomUUID(), List.of(), List.of(), "", 1000);
        ledger.stage(traveler, pending);
        pending.retain(5000);
        ledger.prune(9000);
        assertEquals(pending, ledger.peek(traveler));
        assertEquals(false, pending.dispatched());
        ledger.prune(10_001);
        assertNull(ledger.peek(traveler));
    }

    private static final class Fixture {
        private final UUID portal = UUID.randomUUID();
        private final UUID traveler = UUID.randomUUID();
        private final RuleTraversalLedger ledger = new RuleTraversalLedger();
        private final WarmupTracker warmups = new WarmupTracker(mock(WarmupTracker.Pinner.class));
        private final RuleAdmission admission = new RuleAdmission(warmups, ledger);
        private final ChargePool charges = new ChargePool(() -> 60_000L);
        private final RuleEvaluation context = mock(RuleEvaluation.class);
        private final RuleCostReservation.Subject<String> subject = subject();
        private final RuleDocument document;

        private Fixture(TraversalProfile profile) {
            document = new RuleDocument(List.of(new Rule("charge", List.of(), RuleOutcome.allow(),
                List.of(new Cost.Charge(1)), List.of())), RuleOutcome.allow(), profile, 0);
            charges.reshape(profile, 1000);
            when(context.travelerId()).thenReturn(traveler);
            when(context.isPlayer()).thenReturn(true);
        }

        private RuleAdmission.Decision evaluate(long now, boolean screening) {
            when(context.nowMillis()).thenReturn(now);
            return admission.evaluate(new RuleAdmission.Attempt(portal, document, CompiledRules.compile(document), charges,
                context, TraversalPhase.DEPART, screening, subject, new WarmupTracker.Anchor("world", 0, 0)));
        }

        @SuppressWarnings("unchecked")
        private static RuleCostReservation.Subject<String> subject() {
            return mock(RuleCostReservation.Subject.class);
        }
    }
}
