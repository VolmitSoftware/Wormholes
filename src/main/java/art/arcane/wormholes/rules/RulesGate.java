package art.arcane.wormholes.rules;

import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.hook.TraversalAttempt;
import art.arcane.wormholes.hook.TraversalGate;
import art.arcane.wormholes.hook.TraversalVerdict;
import art.arcane.wormholes.localization.RulesMessages;
import art.arcane.wormholes.portal.LocalPortal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Applies a portal's rules document to every crossing. Departures run the full chain and then the profile
 * cooldown and charge pool; arrivals only run the rules that filter on traveler class or type, so a portal
 * cannot refuse someone for a reason they could not have seen before stepping in. A rig member being
 * screened is judged on the rules alone: the root carries the cost, the cooldown and the warmup.
 *
 * <p>A portal with no document and no profile costs one map lookup and returns the allow identity.</p>
 */
public final class RulesGate implements TraversalGate {
    public static final String BYPASS_PERMISSION = RuleAdmission.BYPASS_PERMISSION;

    private final RulesEnvironment environment;
    private final RuleAdmission admission;

    RulesGate(RulesEnvironment environment, WarmupTracker warmups, RuleTraversalLedger ledger) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.admission = new RuleAdmission(warmups, ledger);
    }

    @Override
    public int order() {
        return ORDER_RULES;
    }

    @Override
    public TraversalVerdict evaluate(TraversalAttempt attempt) {
        LocalPortal portal = attempt.portal();
        RulesPortalExtension extension = portal.extension(RulesPortalExtension.class);
        if (extension == null) {
            return TraversalVerdict.ALLOW;
        }
        Entity traveler = attempt.traveler();
        Player player = traveler instanceof Player found ? found : null;
        RuleContext context = new RuleContext(portal, traveler, player, attempt.nowMillis(), false, environment);
        RuleAdmission.Decision decision = admission.evaluate(new RuleAdmission.Attempt(portal.getId(),
            extension.document(), extension.compiled(), extension.charges(), context, attempt.phase(),
            attempt.screening(), player == null ? null : new BukkitRuleCostSubject(player),
            player == null ? null : RuleContext.anchor(player.getLocation())));
        return switch (decision) {
            case RuleAdmission.Allow ignored -> TraversalVerdict.ALLOW;
            case RuleAdmission.DeniedOutcome denied -> deny(portal, denied.match());
            case RuleAdmission.DeniedCharges denied -> denyKey(RulesMessages.DENIED_CHARGES, portal, denied.match());
            case RuleAdmission.DeniedCost denied -> denyCost(portal, denied.cost());
            case RuleAdmission.DeniedCooldown denied -> new TraversalVerdict.Deny(RulesMessages.DENIED_COOLDOWN,
                RuleMessageArgs.of().with("portal", portal.getName())
                    .with("seconds", Long.valueOf((denied.remainingMillis() + 999L) / 1000L))
                    .forKey(RulesMessages.DENIED_COOLDOWN), true);
            case RuleAdmission.DeferredWarmup ignored -> new TraversalVerdict.Defer(RulesMessages.WARMUP_COUNTDOWN);
            case RuleAdmission.CancelledWarmup ignored -> new TraversalVerdict.Deny(RulesMessages.WARMUP_CANCELLED,
                RuleMessageArgs.of().forKey(RulesMessages.WARMUP_CANCELLED), true);
        };
    }

    private static TraversalVerdict denyCost(LocalPortal portal, Cost cost) {
        TextKey reason = RulesMessages.DENIED_COST;
        return new TraversalVerdict.Deny(reason, RuleMessageArgs.of()
            .with("portal", portal.getName())
            .with("amount", RuleCostText.describe(cost))
            .forKey(reason), true);
    }

    private TraversalVerdict deny(LocalPortal portal, CompiledRules.Match match) {
        return denyKey(RulesMessages.denial(match.outcome().reason()), portal, match);
    }

    private TraversalVerdict denyKey(TextKey reason, LocalPortal portal, CompiledRules.Match match) {
        return new TraversalVerdict.Deny(reason, RuleMessageArgs.of()
            .with("portal", portal.getName())
            .with("mode", travelerFilterLabel(match))
            .forKey(reason), true);
    }

    /** Describes the traveler classes the matched rule names, for the "only {mode} may use this portal" refusal. */
    private static String travelerFilterLabel(CompiledRules.Match match) {
        if (match.rule() == null) {
            return "";
        }
        for (Condition condition : match.rule().conditions()) {
            if (condition instanceof Condition.EntityClass filter) {
                StringJoiner joiner = new StringJoiner(", ");
                for (TravelerClass kind : filter.classes()) {
                    joiner.add(kind.name().toLowerCase(Locale.ROOT).replace('_', ' '));
                }
                return joiner.toString();
            }
        }
        return "";
    }
}
