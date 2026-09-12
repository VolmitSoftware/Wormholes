package art.arcane.wormholes.ops.importers;

import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Multiverse-Portals: {@code plugins/Multiverse-Portals/portals.yml}. Locations are
 * {@code x,y,z:x,y,z} corner pairs; only {@code p:} destinations name another portal, so world and
 * exact destinations are reported rather than guessed at.
 */
public final class MultiversePortalsImporter implements PortalImporter {
    private static final String FILE = "plugins/Multiverse-Portals/portals.yml";

    @Override
    public String id() {
        return "multiverse";
    }

    @Override
    public boolean detect(Path serverRoot) {
        return Files.isRegularFile(serverRoot.resolve(FILE));
    }

    @Override
    public PortalImportReport importFrom(Path serverRoot, boolean dryRun, PortalFactoryBridge factory) {
        PortalImportReport report = new PortalImportReport(id(), dryRun);
        ConfigurationSection root = YamlDocuments.read(serverRoot.resolve(FILE));
        ConfigurationSection portals = root == null ? null : root.getConfigurationSection("portals");
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
            int[] corners = corners(YamlDocuments.stringAt(portal, "location", ""));
            if (corners == null) {
                report.skipped(name, "unreadable location");
                continue;
            }
            RegionFace face = RegionFace.of(corners[0], corners[1], corners[2], corners[3], corners[4], corners[5]);
            if (face == null) {
                report.skipped(name, "region is not a flat face on one horizontal axis");
                continue;
            }
            String destination = YamlDocuments.stringAt(portal, "destination", "");
            String portalDestination = destination.startsWith("p:") ? destination.substring(2) : "";
            if (!destination.isEmpty() && portalDestination.isEmpty()) {
                report.note(name + " pointed at " + destination + ", which is a location, not a portal.");
            }
            ImportedPortal imported = new ImportedPortal(name, world, face.x(), face.y(), face.z(),
                face.facing(), face.width(), face.height(), portalDestination);
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
            if (!portalDestination.isEmpty()) {
                pendingLinks.put(built.portalId(), portalDestination);
            }
        }
        for (Map.Entry<UUID, String> link : pendingLinks.entrySet()) {
            if (!factory.link(link.getKey(), link.getValue())) {
                report.unlinked(link.getValue());
            }
        }
        return report;
    }

    private static int[] corners(String location) {
        String[] halves = location.split(":");
        if (halves.length != 2) {
            return null;
        }
        int[] corners = new int[6];
        for (int half = 0; half < 2; half++) {
            String[] parts = halves[half].split(",");
            if (parts.length != 3) {
                return null;
            }
            for (int axis = 0; axis < 3; axis++) {
                try {
                    corners[half * 3 + axis] = (int) Math.floor(Double.parseDouble(parts[axis].trim()));
                } catch (NumberFormatException malformed) {
                    return null;
                }
            }
        }
        return corners;
    }
}
