package art.arcane.wormholes.rules;

import java.util.List;
import java.util.StringJoiner;

/** Short operator-readable names for rule costs, used by refusals and the route card. */
final class RuleCostText {
    private RuleCostText() {
    }

    static String describe(Cost cost) {
        return switch (cost) {
            case null -> "";
            case Cost.Item value -> value.quantity() + "x " + value.matcher().material();
            case Cost.Vault value -> value.amount().toPlainString();
            case Cost.Xp value -> value.amount() + (value.levels() ? " levels" : " xp");
            case Cost.Hunger value -> value.points() + " hunger";
            case Cost.Health value -> value.points() + " health";
            case Cost.Durability value -> value.points() + " durability";
            case Cost.Charge value -> value.count() + " charges";
            case Cost.Ticket value -> value.uses() + " ticket uses";
        };
    }

    static String describe(List<Cost> costs) {
        StringJoiner joiner = new StringJoiner(" + ");
        for (Cost cost : costs) {
            joiner.add(describe(cost));
        }
        return joiner.toString();
    }
}
