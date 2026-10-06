package art.arcane.wormholes.ops.importers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import art.arcane.optics.math.Face;

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
        JsonArray portals;
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8).trim();
            JsonElement parsed = JsonParser.parseString(content);
            portals = parsed.isJsonArray() ? parsed.getAsJsonArray() : parsed.getAsJsonObject().getAsJsonArray("portals");
            if (portals == null) {
                throw new IllegalArgumentException("Missing portals array");
            }
        } catch (IOException | RuntimeException unreadable) {
            report.skipped(FILE, "unreadable: " + unreadable.getMessage());
            return report;
        }
        for (int index = 0; index < portals.size(); index++) {
            JsonObject portal = portals.get(index).isJsonObject() ? portals.get(index).getAsJsonObject() : null;
            if (portal == null) {
                report.skipped("entry " + index, "not a portal object");
                continue;
            }
            String name = string(portal, "name", "betterportal-" + index);
            JsonObject origin = object(portal, "originPos");
            String world = origin == null ? "" : string(origin, "world", "");
            if (world.isEmpty()) {
                report.skipped(name, "no world on the origin position");
                continue;
            }
            JsonObject size = object(portal, "portalSize");
            ImportedPortal imported = new ImportedPortal(name, world,
                integer(origin, "x", 0), integer(origin, "y", 0), integer(origin, "z", 0),
                facing(string(portal, "portalDirection", "NORTH")),
                size == null ? DEFAULT_WIDTH : Math.max(1, integer(size, "x", DEFAULT_WIDTH)),
                size == null ? DEFAULT_HEIGHT : Math.max(1, integer(size, "y", DEFAULT_HEIGHT)),
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

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String string(JsonObject parent, String key, String fallback) {
        JsonElement value = parent.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    private static int integer(JsonObject parent, String key, int fallback) {
        JsonElement value = parent.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return value.getAsInt();
        } catch (NumberFormatException malformed) {
            return fallback;
        }
    }

    private static Face facing(String direction) {
        return switch (direction.toUpperCase()) {
            case "EAST" -> Face.E;
            case "WEST" -> Face.W;
            case "SOUTH" -> Face.S;
            default -> Face.N;
        };
    }
}
