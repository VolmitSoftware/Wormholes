package art.arcane.wormholes.ops.importers;

import art.arcane.wormholes.util.Direction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Legacy Stargate portal databases: {@code plugins/Stargate/portals/<world>.db}, one gate per line as
 * {@code name:button:origin:modX,modZ,rotX:gateFile:destination:network:owner:...}. Gates become
 * portals named {@code <network>:<name>} with a one-way link to their destination gate.
 */
public final class StargateImporter implements PortalImporter {
    private static final String FOLDER = "plugins/Stargate/portals";
    private static final int MIN_FIELDS = 7;
    private static final int DEFAULT_WIDTH = 2;
    private static final int DEFAULT_HEIGHT = 3;

    @Override
    public String id() {
        return "stargate";
    }

    @Override
    public boolean detect(Path serverRoot) {
        return Files.isDirectory(serverRoot.resolve(FOLDER));
    }

    @Override
    public PortalImportReport importFrom(Path serverRoot, boolean dryRun, PortalFactoryBridge factory) {
        PortalImportReport report = new PortalImportReport(id(), dryRun);
        Path folder = serverRoot.resolve(FOLDER);
        if (!Files.isDirectory(folder)) {
            return report;
        }
        List<Path> databases = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder)) {
            databases.addAll(files.filter(file -> file.getFileName().toString().endsWith(".db")).sorted().toList());
        } catch (IOException unreadable) {
            report.skipped(folder.toString(), "unreadable: " + unreadable.getMessage());
            return report;
        }

        Map<UUID, String> pendingLinks = new LinkedHashMap<>();
        boolean sawNetwork = false;
        for (Path database : databases) {
            String world = database.getFileName().toString().replace(".db", "");
            List<String> lines;
            try {
                lines = Files.readAllLines(database, StandardCharsets.UTF_8);
            } catch (IOException unreadable) {
                report.skipped(database.toString(), "unreadable: " + unreadable.getMessage());
                continue;
            }
            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                String[] fields = line.split(":", -1);
                if (fields.length < MIN_FIELDS) {
                    report.skipped(line.length() > 32 ? line.substring(0, 32) : line, "unrecognized Stargate line");
                    continue;
                }
                String gateName = fields[0].trim();
                int[] origin = coordinates(fields[2]);
                if (origin == null) {
                    report.skipped(gateName, "unreadable gate coordinates");
                    continue;
                }
                String network = fields[6].trim();
                sawNetwork |= !network.isEmpty();
                String name = network.isEmpty() ? gateName : network + ":" + gateName;
                String destination = fields[5].trim();
                String destinationName = destination.isEmpty() ? ""
                    : (network.isEmpty() ? destination : network + ":" + destination);
                ImportedPortal portal = new ImportedPortal(name, world, origin[0], origin[1], origin[2],
                    facing(fields[3]), DEFAULT_WIDTH, DEFAULT_HEIGHT, destinationName);
                if (dryRun) {
                    report.created(name);
                    continue;
                }
                PortalFactoryBridge.CreateResult built = factory.create(portal);
                if (!built.ok()) {
                    report.skipped(name, built.reason());
                    continue;
                }
                report.created(name);
                if (!destinationName.isEmpty()) {
                    pendingLinks.put(built.portalId(), destinationName);
                }
            }
        }

        for (Map.Entry<UUID, String> link : pendingLinks.entrySet()) {
            if (!factory.link(link.getKey(), link.getValue())) {
                report.unlinked(link.getValue());
            }
        }
        if (sawNetwork) {
            report.note("Stargate network names became portal name prefixes; rebuild them as Wormholes networks.");
        }
        return report;
    }

    private static int[] coordinates(String field) {
        String[] parts = field.split(",");
        if (parts.length != 3) {
            return null;
        }
        int[] coordinates = new int[3];
        for (int index = 0; index < 3; index++) {
            try {
                coordinates[index] = (int) Math.floor(Double.parseDouble(parts[index].trim()));
            } catch (NumberFormatException malformed) {
                return null;
            }
        }
        return coordinates;
    }

    /** Stargate stores the gate's outward vector as {@code modX,modZ,rotX}. */
    private static Direction facing(String field) {
        String[] parts = field.split(",");
        if (parts.length < 2) {
            return Direction.N;
        }
        int modX = (int) Math.signum(parseOrZero(parts[0]));
        int modZ = (int) Math.signum(parseOrZero(parts[1]));
        if (modX > 0) {
            return Direction.E;
        }
        if (modX < 0) {
            return Direction.W;
        }
        if (modZ > 0) {
            return Direction.S;
        }
        return Direction.N;
    }

    private static double parseOrZero(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException malformed) {
            return 0.0D;
        }
    }
}
