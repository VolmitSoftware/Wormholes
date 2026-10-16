package art.arcane.wormholes.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class RuleAuthoring {
    public static final String CONSOLE_EFFECT_REFUSED = "Console command effects require an administrator and enabled console effects";

    private RuleAuthoring() {
    }

    public static Optional<RuleDocument> author(RuleDocument document, UUID authorId, boolean administrator, boolean consoleEffectsAllowed) {
        Objects.requireNonNull(authorId, "authorId");
        List<Rule> rules = new ArrayList<>(document.rules().size());
        for (Rule rule : document.rules()) {
            List<Effect> effects = new ArrayList<>(rule.effects().size());
            for (Effect effect : rule.effects()) {
                if (!(effect instanceof Effect.Command command)) {
                    effects.add(effect);
                    continue;
                }
                if (command.asConsole() && (!administrator || !consoleEffectsAllowed)) {
                    return Optional.empty();
                }
                effects.add(new Effect.Command(command.line(), command.asConsole(), authorId));
            }
            rules.add(new Rule(rule.id(), rule.conditions(), rule.outcome(), rule.costs(), effects));
        }
        return Optional.of(document.withRules(rules));
    }
}
