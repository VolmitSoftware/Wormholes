package art.arcane.wormholes.ops.importers;

import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.util.Direction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * BetterPortals: {@code plugins/BetterPortals/portals.json}, either a {@code portals} array or a bare
 * array of entries carrying an origin position, a facing, and a portal size.
 */
public final class BetterPortalsImporter implements PortalImporter {
    private static final String FILE = "plugins/BetterPortals/portals.json";
    private static final int DEFAULT_WIDTH = 2;
    private static final int DEFAULT_HEIGHT = 3;

    @Override
    public String id() {
        return "betterportals";
    }

    @Override
    public boolean detect(Path serverRoot) {
        return Files.isRegularFile(serverRoot.resolve(FILE));
    }

    @Override
    public PortalImportReport importFrom(Path serverRoot, boolean dryRun, PortalFactoryBridge factory) {
        PortalImportReport report = new PortalImportReport(id(), dryRun);
        Path file = serverRoot.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return report;
        }
        JSONArray portals;
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8).trim();
            portals = content.startsWith("[") ? new JSONArray(content) : new JSONObject(content).getJSONArray("portals");
        } catch (IOException | RuntimeException unreadable) {
            report.skipped(FILE, "unreadable: " + unreadable.getMessage());
            return report;
        }
        for (int index = 0; index < portals.length(); index++) {
            JSONObject portal = portals.optJSONObject(index);
            if (portal == null) {
                report.skipped("entry " + index, "not a portal object");
                continue;
            }
            String name = portal.optString("name", "betterportal-" + index);
            JSONObject origin = portal.optJSONObject("originPos");
            String world = origin == null ? "" : origin.optString("world", "");
            if (world.isEmpty()) {
                report.skipped(name, "no world on the origin position");
                continue;
            }
            JSONObject size = portal.optJSONObject("portalSize");
            ImportedPortal imported = new ImportedPortal(name, world,
                origin.optInt("x", 0), origin.optInt("y", 0), origin.optInt("z", 0),
                facing(portal.optString("portalDirection", "NORTH")),
                size == null ? DEFAULT_WIDTH : Math.max(1, size.optInt("x", DEFAULT_WIDTH)),
                size == null ? DEFAULT_HEIGHT : Math.max(1, size.optInt("y", DEFAULT_HEIGHT)),
                "");
            if (dryRun) {
                report.created(name);
                continue;
            }
            PortalFactoryBridge.CreateResult built = factory.create(imported);
            if (built.ok()) {
                report.created(name);
            } else {
                report.skipped(name, built.reason());
            }
        }
        return report;
    }

    private static Direction facing(String direction) {
        return switch (direction.toUpperCase()) {
            case "EAST" -> Direction.E;
            case "WEST" -> Direction.W;
            case "SOUTH" -> Direction.S;
            default -> Direction.N;
        };
    }
}
