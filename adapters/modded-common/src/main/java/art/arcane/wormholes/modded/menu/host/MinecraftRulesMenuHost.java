package art.arcane.wormholes.modded.menu.host;

import art.arcane.wormholes.config.toml.RulesConfig;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.rules.RulesMenuHost;
import art.arcane.wormholes.menu.rules.RulesMenuView;
import art.arcane.wormholes.menu.rules.RulesStoreOutcome;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.rules.RuleDocument;
import art.arcane.wormholes.rules.RuleTemplates;
import art.arcane.wormholes.rules.RuleValidationException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class MinecraftRulesMenuHost implements RulesMenuHost {
    private final WormholesModRuntime runtime;

    public MinecraftRulesMenuHost(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public Optional<RulesMenuView> rules(UUID portalId) {
        MinecraftPortal portal = runtime.portals().get(portalId);
        if (portal == null) {
            return Optional.empty();
        }
        return Optional.of(new RulesMenuView(runtime.rules().document(portal), limits()));
    }

    @Override
    public RulesStoreOutcome store(MenuViewer viewer, UUID portalId, RuleDocument document) {
        MinecraftPortal portal = runtime.portals().get(portalId);
        if (portal == null) {
            return RulesStoreOutcome.invalid(List.of("portal " + portalId + " has no rules"));
        }
        try {
            runtime.rules().setDocument(portal, document);
        } catch (RuleValidationException invalid) {
            return RulesStoreOutcome.invalid(invalid.problems());
        }
        runtime.menus().invalidate(new MenuScope.Portal(portalId));
        return RulesStoreOutcome.stored();
    }

    @Override
    public List<String> templates() {
        return templateStore().list();
    }

    @Override
    public Optional<RuleDocument> template(String name) {
        return Optional.ofNullable(templateStore().load(name));
    }

    private RuleTemplates templateStore() {
        return new RuleTemplates(runtime.stores().data(), limits());
    }

    private RulesConfig limits() {
        return runtime.configuration().settings().getRules();
    }
}
