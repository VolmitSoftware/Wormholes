package art.arcane.wormholes.menu.host;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.rules.RulesMenuHost;
import art.arcane.wormholes.menu.rules.RulesMenuView;
import art.arcane.wormholes.menu.rules.RulesStoreOutcome;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.rules.RuleDocument;
import art.arcane.wormholes.rules.RuleDocumentCodec;
import art.arcane.wormholes.rules.RuleTemplates;
import art.arcane.wormholes.rules.RulesLimits;
import art.arcane.wormholes.rules.RulesPortalExtension;

public final class BukkitRulesMenuHost implements RulesMenuHost {
    private final PortalManager portals;
    private final Path dataFolder;

    public BukkitRulesMenuHost(PortalManager portals, Path dataFolder) {
        this.portals = Objects.requireNonNull(portals, "portals");
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder");
    }

    @Override
    public Optional<RulesMenuView> rules(UUID portalId) {
        return BukkitLocalPortals.local(portals, portalId).map(BukkitRulesMenuHost::extension)
            .map(extension -> new RulesMenuView(extension.document(), RulesLimits.config()));
    }

    @Override
    public RulesStoreOutcome store(MenuViewer viewer, UUID portalId, RuleDocument document) {
        Optional<LocalPortal> found = BukkitLocalPortals.local(portals, portalId);
        RulesPortalExtension extension = found.map(BukkitRulesMenuHost::extension).orElse(null);
        if (extension == null) {
            return RulesStoreOutcome.invalid(List.of("portal " + portalId + " has no rules"));
        }
        List<String> problems = new ArrayList<>();
        RuleDocument validated = RuleDocumentCodec.decode(RuleDocumentCodec.toJson(document), problems, RulesLimits.config());
        if (!problems.isEmpty()) {
            return RulesStoreOutcome.invalid(problems);
        }
        extension.setDocument(validated);
        found.get().invalidateMenus();
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
        return new RuleTemplates(dataFolder, RulesLimits.config());
    }

    private static RulesPortalExtension extension(LocalPortal portal) {
        return portal.extension(RulesPortalExtension.class);
    }
}
