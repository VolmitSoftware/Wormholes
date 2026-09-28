package art.arcane.wormholes.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * A {@link RuleDocument} turned into arrays of predicates evaluated in order with early-out. Each rule's
 * conditions are sorted cheap-first so an entity-class mismatch settles a rule before a permission lookup, a
 * permission lookup before an advancement, and an advancement before a PlaceholderAPI expansion.
 *
 * <p>Compilation happens once per document revision and the result is cached on the portal extension.</p>
 */
public final class CompiledRules {
    private final long revision;
    private final TraversalProfile profile;
    private final RuleOutcome defaultOutcome;
    private final List<CompiledRule> rules;

    private CompiledRules(long revision, TraversalProfile profile, RuleOutcome defaultOutcome, List<CompiledRule> rules) {
        this.revision = revision;
        this.profile = profile;
        this.defaultOutcome = defaultOutcome;
        this.rules = rules;
    }

    public static CompiledRules compile(RuleDocument document) {
        RuleDocument source = document == null ? RuleDocument.EMPTY : document;
        List<CompiledRule> compiled = new ArrayList<>(source.rules().size());
        for (Rule rule : source.rules()) {
            compiled.add(CompiledRule.of(rule));
        }
        return new CompiledRules(source.revision(), source.profile(), source.defaultOutcome(), List.copyOf(compiled));
    }

    public long revision() {
        return revision;
    }

    public TraversalProfile profile() {
        return profile;
    }

    public RuleOutcome defaultOutcome() {
        return defaultOutcome;
    }

    /** Runs every rule in order; the first whose conditions all match decides. */
    public Match evaluate(RuleEvaluation context) {
        for (CompiledRule rule : rules) {
            if (rule.matches(context)) {
                return rule.match();
            }
        }
        return new Match(null, defaultOutcome, List.of(), List.of());
    }

    /**
     * Arrival gating: walks the rules in order and stops at the first one that matches, exactly as a
     * departure does, but honours its outcome only when every condition is a traveler class or type
     * filter. Arrivals are gated on who the traveler is, never on what they hold, can afford, or have
     * permission for on the far side - and a rule that already settled the case is not skipped over,
     * which would let a lower catch-all refuse a traveler an earlier rule allowed.
     */
    public Match evaluateTravelerFilters(RuleEvaluation context) {
        for (CompiledRule rule : rules) {
            if (!rule.matches(context)) {
                continue;
            }
            return rule.travelerFilterOnly() ? rule.match() : arrivalAllow();
        }
        return arrivalAllow();
    }

    private static Match arrivalAllow() {
        return new Match(null, RuleOutcome.allow(), List.of(), List.of());
    }

    /** The rule that decided, null when the default applied, plus what it charges and does. */
    public record Match(Rule rule, RuleOutcome outcome, List<Cost> costs, List<Effect> effects) {
    }

    private static final class CompiledRule {
        private final List<Predicate<RuleEvaluation>> predicates;
        private final boolean travelerFilterOnly;
        private final Match match;

        private CompiledRule(Rule rule, List<Predicate<RuleEvaluation>> predicates, boolean travelerFilterOnly) {
            this.predicates = predicates;
            this.travelerFilterOnly = travelerFilterOnly;
            this.match = new Match(rule, rule.outcome(), rule.costs(), rule.effects());
        }

        static CompiledRule of(Rule rule) {
            List<Condition> ordered = new ArrayList<>(rule.conditions());
            ordered.sort(Comparator.comparingInt(CompiledRules::costRank));
            List<Predicate<RuleEvaluation>> predicates = new ArrayList<>(ordered.size());
            boolean travelerFilterOnly = !rule.conditions().isEmpty();
            for (Condition condition : ordered) {
                predicates.add(predicate(condition));
                travelerFilterOnly &= condition instanceof Condition.EntityClass || condition instanceof Condition.EntityTypes;
            }
            return new CompiledRule(rule, List.copyOf(predicates), travelerFilterOnly);
        }

        boolean matches(RuleEvaluation context) {
            for (Predicate<RuleEvaluation> predicate : predicates) {
                if (!predicate.test(context)) {
                    return false;
                }
            }
            return true;
        }

        boolean travelerFilterOnly() {
            return travelerFilterOnly;
        }

        Match match() {
            return match;
        }
    }

    private static int costRank(Condition condition) {
        return switch (condition) {
            case Condition.EntityClass ignored -> 0;
            case Condition.EntityTypes ignored -> 1;
            case Condition.PortalState ignored -> 2;
            case Condition.TimeWindow ignored -> 3;
            case Condition.Weather ignored -> 4;
            case Condition.MoonPhase ignored -> 5;
            case Condition.PlayerState ignored -> 6;
            case Condition.Redstone ignored -> 7;
            case Condition.Permission ignored -> 8;
            case Condition.KeyItem ignored -> 9;
            case Condition.HeldItem ignored -> 10;
            case Condition.OwnsItem ignored -> 11;
            case Condition.Advancement ignored -> 12;
            case Condition.Papi ignored -> 13;
        };
    }

    private static Predicate<RuleEvaluation> predicate(Condition condition) {
        return switch (condition) {
            case Condition.EntityClass value -> context -> matchesClass(value, context);
            case Condition.EntityTypes value -> context -> value.keys().contains(context.entityTypeKey());
            case Condition.PortalState value -> context -> matchesPortalState(value, context);
            case Condition.TimeWindow value -> context -> inTimeWindow(value, context.worldTimeTicks());
            case Condition.Weather value -> context -> value.kind() == context.weather();
            case Condition.MoonPhase value -> context -> value.phases().contains(Integer.valueOf(context.moonPhase()));
            case Condition.PlayerState value -> context -> matchesPlayerState(value, context);
            case Condition.Redstone value -> context -> context.redstonePowered(value.dx(), value.dy(), value.dz()) == value.powered();
            case Condition.Permission value -> context -> context.hasPermission(value.node());
            case Condition.KeyItem value -> context -> context.isPlayer() && context.holdsKey(value.keyId());
            case Condition.HeldItem value -> context -> context.isPlayer() && context.holdsItem(value.matcher(), value.hand());
            case Condition.OwnsItem value -> context -> context.isPlayer() && context.countItems(value.matcher()) >= value.count();
            case Condition.Advancement value -> context -> context.isPlayer() && ConditionCache.get(context.travelerId(),
                "advancement:" + value.key(), context.nowMillis(), context.expensiveConditionCacheMillis(),
                () -> context.hasAdvancement(value.key()));
            case Condition.Papi value -> context -> context.isPlayer() && ConditionCache.get(context.travelerId(),
                "papi:" + value.expression() + value.operator() + value.value(), context.nowMillis(),
                context.expensiveConditionCacheMillis(),
                () -> compare(context.placeholder(value.expression()), value.operator(), value.value()));
        };
    }

    private static boolean matchesClass(Condition.EntityClass condition, RuleEvaluation context) {
        boolean member = false;
        for (TravelerClass kind : condition.classes()) {
            if (context.isClass(kind)) {
                member = true;
                break;
            }
        }
        return member != condition.negate();
    }

    private static boolean matchesPortalState(Condition.PortalState condition, RuleEvaluation context) {
        if (context.portalOpen() != condition.open()) {
            return false;
        }
        if (condition.dialedAddress().isEmpty()) {
            return true;
        }
        return condition.dialedAddress().equalsIgnoreCase(context.dialedAddress());
    }

    private static boolean inTimeWindow(Condition.TimeWindow window, long timeTicks) {
        long time = Math.floorMod(timeTicks, 24000L);
        if (window.startTick() <= window.endTick()) {
            return time >= window.startTick() && time <= window.endTick();
        }
        return time >= window.startTick() || time <= window.endTick();
    }

    private static boolean matchesPlayerState(Condition.PlayerState condition, RuleEvaluation context) {
        if (!context.isPlayer()) {
            return false;
        }
        double value = context.playerState(condition.field());
        return value >= condition.min() && value <= condition.max();
    }

    private static boolean compare(String actual, Condition.Comparator operator, String expected) {
        String left = actual == null ? "" : actual.trim();
        String right = expected == null ? "" : expected.trim();
        return switch (operator) {
            case EQUALS -> left.equalsIgnoreCase(right);
            case NOT_EQUALS -> !left.equalsIgnoreCase(right);
            case CONTAINS -> left.toLowerCase(Locale.ROOT).contains(right.toLowerCase(Locale.ROOT));
            case LESS, LESS_OR_EQUAL, GREATER, GREATER_OR_EQUAL -> compareNumbers(left, operator, right);
        };
    }

    private static boolean compareNumbers(String actual, Condition.Comparator operator, String expected) {
        double left;
        double right;
        try {
            left = Double.parseDouble(actual);
            right = Double.parseDouble(expected);
        } catch (NumberFormatException notNumeric) {
            return false;
        }
        return switch (operator) {
            case LESS -> left < right;
            case LESS_OR_EQUAL -> left <= right;
            case GREATER -> left > right;
            case GREATER_OR_EQUAL -> left >= right;
            default -> false;
        };
    }
}
