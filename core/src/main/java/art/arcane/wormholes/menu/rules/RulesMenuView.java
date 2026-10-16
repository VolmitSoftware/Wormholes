package art.arcane.wormholes.menu.rules;

import java.util.Objects;

import art.arcane.wormholes.config.toml.RulesConfig;
import art.arcane.wormholes.rules.RuleDocument;

public record RulesMenuView(RuleDocument document, RulesConfig limits) {
    public RulesMenuView {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(limits, "limits");
    }
}
