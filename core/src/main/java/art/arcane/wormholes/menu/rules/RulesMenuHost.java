package art.arcane.wormholes.menu.rules;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.rules.RuleDocument;

public interface RulesMenuHost {
    Optional<RulesMenuView> rules(UUID portalId);

    RulesStoreOutcome store(MenuViewer viewer, UUID portalId, RuleDocument document);

    List<String> templates();

    Optional<RuleDocument> template(String name);
}
