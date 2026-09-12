package art.arcane.wormholes.rules;

import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.hook.TraversalAttempt;
import art.arcane.wormholes.hook.TraversalGate;
import art.arcane.wormholes.hook.TraversalPhase;
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
    public static final String BYPASS_PERMISSION = "wormholes.rules.bypass";

    private final RulesEnvironment environment;
    private final WarmupTracker warmups;
    private final RuleTraversalLedger ledger;

    RulesGate(RulesEnvironment environment, WarmupTracker warmups, RuleTraversalLedger ledger) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.warmups = Objects.requireNonNull(warmups, "warmups");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
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
        RuleDocument document = extension.document();
        if (document.isInert()) {
            return TraversalVerdict.ALLOW;
        }
        Entity traveler = attempt.traveler();
        if (environment.hasPermission(traveler, BYPASS_PERMISSION)) {
            return TraversalVerdict.ALLOW;
        }
        RuleContext context = new RuleContext(portal, traveler, traveler instanceof Player player ? player : null,
            attempt.nowMillis(), false, environment);
        CompiledRules compiled = extension.compiled();
        if (attempt.phase() == TraversalPhase.ARRIVE) {
            CompiledRules.Match arrival = compiled.evaluateTravelerFilters(context);
            return arrival.outcome().allowed() ? TraversalVerdict.ALLOW : deny(portal, arrival);
        }
        CompiledRules.Match match = compiled.evaluate(context);
        if (!match.outcome().allowed()) {
            return deny(portal, match);
        }
        if (attempt.screening()) {
            return TraversalVerdict.ALLOW;
        }
        TraversalVerdict cooldown = cooldownVerdict(portal, traveler, document.profile(), attempt.nowMillis());
        if (cooldown != null) {
            return cooldown;
        }
        if (!extension.charges().canConsume(chargeCost(match), attempt.nowMillis())) {
            return denyKey(RulesMessages.DENIED_CHARGES, portal, match);
        }
        TraversalVerdict warmup = warmupVerdict(portal, context, document.profile());
        if (!(warmup instanceof TraversalVerdict.Allow)) {
            return warmup;
        }
        return admitVerdict(portal, context, extension, match);
    }

    /**
     * Tests the matched rule's costs and stages them for settlement. Nothing is taken here: the traversal
     * loop re-evaluates every gate for every capture-zone entity on every portal tick, so a gate that
     * charged would charge again for every tick a later gate defers. {@link RulesObserver} takes the costs
     * once, when the departure settles.
     */
    private TraversalVerdict admitVerdict(LocalPortal portal, RuleContext context, RulesPortalExtension extension,
                                          CompiledRules.Match match) {
        if (match.costs().isEmpty() && match.effects().isEmpty()) {
            return TraversalVerdict.ALLOW;
        }
        Player player = context.player();
        if (player == null) {
            return match.costs().isEmpty() ? TraversalVerdict.ALLOW : denyCost(portal, match.costs().getFirst());
        }
        Cost unpayable = RuleCostReservation.firstUnaffordable(player, match.costs(), extension.charges());
        if (unpayable != null) {
            return denyCost(portal, unpayable);
        }
        ledger.stage(player.getUniqueId(), new RuleTraversalLedger.Pending(portal.getId(), match.costs(),
            match.effects(), extension.document().profile().cooldownGroup(), context.nowMillis()));
        return TraversalVerdict.ALLOW;
    }

    private static TraversalVerdict denyCost(LocalPortal portal, Cost cost) {
        TextKey reason = RulesMessages.DENIED_COST;
        return new TraversalVerdict.Deny(reason, RuleMessageArgs.of()
            .with("portal", portal.getName())
            .with("amount", RuleCostText.describe(cost))
            .forKey(reason), true);
    }

    /**
     * Defers while the traveler's warmup runs and refuses for the grace after one was cancelled. Deferring is
     * what keeps the traveler standing in the aperture: it neither latches the crossing nor bounces them out.
     */
    private TraversalVerdict warmupVerdict(LocalPortal portal, RuleContext context, TraversalProfile profile) {
        Player player = context.player();
        if (player == null || profile.warmupMillis() <= 0L) {
            return TraversalVerdict.ALLOW;
        }
        WarmupTracker.Decision decision = warmups.begin(player.getUniqueId(), portal.getId(), profile.warmupMillis(),
            player.getLocation(), context.nowMillis());
        return switch (decision) {
            case ALLOW -> TraversalVerdict.ALLOW;
            case DEFER -> new TraversalVerdict.Defer(RulesMessages.WARMUP_COUNTDOWN);
            case CANCELLED -> new TraversalVerdict.Deny(RulesMessages.WARMUP_CANCELLED,
                RuleMessageArgs.of().forKey(RulesMessages.WARMUP_CANCELLED), true);
        };
    }

    /** Total charges the matched rule asks for, zero when it asks for none. */
    static int chargeCost(CompiledRules.Match match) {
        int charges = 0;
        for (Cost cost : match.costs()) {
            if (cost instanceof Cost.Charge charge) {
                charges += charge.count();
            }
        }
        return charges;
    }

    private TraversalVerdict cooldownVerdict(LocalPortal portal, Entity traveler, TraversalProfile profile, long nowMillis) {
        if (profile.cooldownMillis() <= 0L && profile.cooldownGroup().isEmpty()) {
            return null;
        }
        long remaining = PortalCooldowns.remainingMillis(traveler.getUniqueId(), portal.getId(), profile.cooldownGroup(), nowMillis);
        if (remaining <= 0L) {
            return null;
        }
        TextKey reason = RulesMessages.DENIED_COOLDOWN;
        return new TraversalVerdict.Deny(reason, RuleMessageArgs.of()
            .with("portal", portal.getName())
            .with("seconds", Long.valueOf((remaining + 999L) / 1000L))
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
