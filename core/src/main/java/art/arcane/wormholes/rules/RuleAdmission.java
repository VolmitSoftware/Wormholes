package art.arcane.wormholes.rules;

import art.arcane.wormholes.hook.TraversalPhase;

import java.util.Objects;
import java.util.UUID;

public final class RuleAdmission {
    public static final String BYPASS_PERMISSION = "wormholes.rules.bypass";

    private final WarmupTracker warmups;
    private final RuleTraversalLedger ledger;

    public RuleAdmission(WarmupTracker warmups, RuleTraversalLedger ledger) {
        this.warmups = Objects.requireNonNull(warmups);
        this.ledger = Objects.requireNonNull(ledger);
    }

    public Decision evaluate(Attempt attempt) {
        RuleDocument document = attempt.document();
        RuleEvaluation context = attempt.context();
        if (document.isInert() || context.hasPermission(BYPASS_PERMISSION)) {
            return Allow.INSTANCE;
        }
        if (attempt.phase() == TraversalPhase.ARRIVE) {
            CompiledRules.Match arrival = attempt.compiled().evaluateTravelerFilters(context);
            return arrival.outcome().allowed() ? Allow.INSTANCE : new DeniedOutcome(arrival);
        }
        CompiledRules.Match match = attempt.compiled().evaluate(context);
        if (!match.outcome().allowed()) {
            return new DeniedOutcome(match);
        }
        if (attempt.screening()) {
            return Allow.INSTANCE;
        }
        TraversalProfile profile = document.profile();
        if (profile.cooldownMillis() > 0L || !profile.cooldownGroup().isEmpty()) {
            long remaining = PortalCooldowns.remainingMillis(context.travelerId(), attempt.portalId(),
                profile.cooldownGroup(), context.nowMillis());
            if (remaining > 0L) {
                return new DeniedCooldown(remaining);
            }
        }
        if (!attempt.charges().canConsume(chargeCost(match), context.nowMillis())) {
            return new DeniedCharges(match);
        }
        if (context.isPlayer() && profile.warmupMillis() > 0L) {
            WarmupTracker.Decision warmup = warmups.begin(context.travelerId(), attempt.portalId(),
                profile.warmupMillis(), attempt.anchor(), context.nowMillis());
            if (warmup == WarmupTracker.Decision.DEFER) {
                return DeferredWarmup.INSTANCE;
            }
            if (warmup == WarmupTracker.Decision.CANCELLED) {
                return CancelledWarmup.INSTANCE;
            }
        }
        return stage(attempt, match);
    }

    public static int chargeCost(CompiledRules.Match match) {
        int charges = 0;
        for (Cost cost : match.costs()) {
            if (cost instanceof Cost.Charge charge) {
                charges += charge.count();
            }
        }
        return charges;
    }

    private Decision stage(Attempt attempt, CompiledRules.Match match) {
        if (match.costs().isEmpty() && match.effects().isEmpty()) {
            return Allow.INSTANCE;
        }
        if (!attempt.context().isPlayer()) {
            return match.costs().isEmpty() ? Allow.INSTANCE : new DeniedCost(match.costs().getFirst());
        }
        Cost unpayable = RuleCostReservation.firstUnaffordable(attempt.subject(), match.costs(), attempt.charges());
        if (unpayable != null) {
            return new DeniedCost(unpayable);
        }
        ledger.stage(attempt.context().travelerId(), new RuleTraversalLedger.Pending(attempt.portalId(), match.costs(),
            match.effects(), attempt.document().profile().cooldownGroup(), attempt.context().nowMillis()));
        return Allow.INSTANCE;
    }

    public record Attempt(UUID portalId, RuleDocument document, CompiledRules compiled, ChargePool charges,
                          RuleEvaluation context, TraversalPhase phase, boolean screening,
                          RuleCostReservation.Subject<?> subject, WarmupTracker.Anchor anchor) {
        public Attempt {
            Objects.requireNonNull(portalId);
            Objects.requireNonNull(document);
            Objects.requireNonNull(compiled);
            Objects.requireNonNull(charges);
            Objects.requireNonNull(context);
            Objects.requireNonNull(phase);
            if (context.isPlayer()) {
                Objects.requireNonNull(subject);
            }
        }
    }

    public sealed interface Decision {
    }

    public enum Allow implements Decision { INSTANCE }

    public record DeniedOutcome(CompiledRules.Match match) implements Decision { }

    public record DeniedCharges(CompiledRules.Match match) implements Decision { }

    public record DeniedCost(Cost cost) implements Decision { }

    public record DeniedCooldown(long remainingMillis) implements Decision { }

    public enum DeferredWarmup implements Decision { INSTANCE }

    public enum CancelledWarmup implements Decision { INSTANCE }
}
