package art.arcane.wormholes.menu.rules;

import java.util.List;

public sealed interface RulesStoreOutcome permits RulesStoreOutcome.Stored, RulesStoreOutcome.Invalid {
    static RulesStoreOutcome stored() {
        return new Stored();
    }

    static RulesStoreOutcome invalid(List<String> problems) {
        return new Invalid(problems);
    }

    record Stored() implements RulesStoreOutcome {
    }

    record Invalid(List<String> problems) implements RulesStoreOutcome {
        public Invalid {
            problems = List.copyOf(problems);
        }
    }
}
