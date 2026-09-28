package art.arcane.wormholes.rules;

import java.util.List;

/**
 * One ordered entry of a {@link RuleDocument}. Every condition must match; the first matching rule decides
 * the traversal and contributes its costs and effects.
 */
public record Rule(String id, List<Condition> conditions, RuleOutcome outcome, List<Cost> costs, List<Effect> effects) {
    public Rule {
        id = id == null ? "" : id.trim();
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        outcome = outcome == null ? RuleOutcome.allow() : outcome;
        costs = costs == null ? List.of() : List.copyOf(costs);
        effects = effects == null ? List.of() : List.copyOf(effects);
    }
}
