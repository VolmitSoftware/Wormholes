package art.arcane.wormholes.ops.importers;

import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Advanced Portals: {@code plugins/AdvancedPortals/portals.yml} regions plus
 * {@code destinations.yml} locations. Regions thicker than one block on both horizontal axes are not
 * apertures and are reported instead of guessed at.
 */
public final class AdvancedPortalsImporter implements PortalImporter {
    private static final String FOLDER = "plugins/AdvancedPortals";

    @Override
    public String id() {
        return "advancedportals";
    }

    @Override
    public boolean detect(Path serverRoot) {
        return Files.isRegularFile(serverRoot.resolve(FOLDER).resolve("portals.yml"));
    }

    @Override
    public PortalImportReport importFrom(Path serverRoot, boolean dryRun, PortalFactoryBridge factory) {
        PortalImportReport report = new PortalImportReport(id(), dryRun);
        ConfigurationSection portals = YamlDocuments.read(serverRoot.resolve(FOLDER).resolve("portals.yml"));
        if (portals == null) {
            return report;
        }
        Map<UUID, String> pendingLinks = new LinkedHashMap<>();
        for (String name : portals.getKeys(false)) {
            ConfigurationSection portal = portals.getConfigurationSection(name);
            if (portal == null) {
                report.skipped(name, "not a portal section");
                continue;
            }
            String world = YamlDocuments.stringAt(portal, "world", "");
            if (world.isEmpty()) {
                report.skipped(name, "no world");
                continue;
            }
            RegionFace face = RegionFace.of(
                YamlDocuments.intAt(portal, "pos1.X", 0),
                YamlDocuments.intAt(portal, "pos1.Y", 0),
                YamlDocuments.intAt(portal, "pos1.Z", 0),
                YamlDocuments.intAt(portal, "pos2.X", 0),
                YamlDocuments.intAt(portal, "pos2.Y", 0),
                YamlDocuments.intAt(portal, "pos2.Z", 0));
            if (face == null) {
                report.skipped(name, "region is not a flat face on one horizontal axis");
                continue;
            }
            String destination = YamlDocuments.stringAt(portal, "destination", "");
            ImportedPortal imported = new ImportedPortal(name, world, face.x(), face.y(), face.z(),
                face.facing(), face.width(), face.height(), destination);
            if (dryRun) {
                report.created(name);
                continue;
            }
            PortalFactoryBridge.CreateResult built = factory.create(imported);
            if (!built.ok()) {
                report.skipped(name, built.reason());
                continue;
            }
            report.created(name);
            if (!destination.isEmpty()) {
                pendingLinks.put(built.portalId(), destination);
            }
        }
        for (Map.Entry<UUID, String> link : pendingLinks.entrySet()) {
            if (!factory.link(link.getKey(), link.getValue())) {
                report.unlinked(link.getValue());
            }
        }
        if (Files.isRegularFile(serverRoot.resolve(FOLDER).resolve("destinations.yml"))) {
            report.note("Entries in destinations.yml are locations, not portals; "
                + "build a portal at each one and link the imports by hand.");
        }
        return report;
    }
}
