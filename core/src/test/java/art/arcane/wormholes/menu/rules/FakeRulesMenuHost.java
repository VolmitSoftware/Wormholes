package art.arcane.wormholes.menu.rules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import art.arcane.wormholes.config.toml.RulesConfig;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.rules.RuleDocument;
import art.arcane.wormholes.rules.RuleDocumentCodec;
import art.arcane.wormholes.rules.RuleValidationException;

final class FakeRulesMenuHost implements RulesMenuHost {
    final RulesConfig limits = new RulesConfig();
    final List<RuleDocument> stored = new ArrayList<>();
    final List<String> templateNames = new ArrayList<>();
    final Map<String, RuleDocument> templates = new HashMap<>();
    RuleDocument document = RuleDocument.EMPTY;
    boolean present = true;

    @Override
    public Optional<RulesMenuView> rules(UUID portalId) {
        return present && portalId.equals(FakeRulesPortals.PORTAL) ? Optional.of(new RulesMenuView(document, limits)) : Optional.empty();
    }

    @Override
    public RulesStoreOutcome store(MenuViewer viewer, UUID portalId, RuleDocument replacement) {
        RuleDocument validated;
        try {
            validated = RuleDocumentCodec.fromJson(RuleDocumentCodec.toJson(replacement), limits).withRevision(document.revision() + 1L);
        } catch (RuleValidationException invalid) {
            return RulesStoreOutcome.invalid(invalid.problems());
        }
        stored.add(validated);
        document = validated;
        return RulesStoreOutcome.stored();
    }

    @Override
    public List<String> templates() {
        return List.copyOf(templateNames);
    }

    @Override
    public Optional<RuleDocument> template(String name) {
        return Optional.ofNullable(templates.get(name));
    }

    RuleDocument last() {
        return stored.get(stored.size() - 1);
    }
}
