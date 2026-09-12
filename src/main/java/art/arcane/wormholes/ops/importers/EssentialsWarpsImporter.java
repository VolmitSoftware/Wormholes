package art.arcane.wormholes.ops.importers;

import art.arcane.wormholes.util.Direction;
import org.bukkit.configuration.ConfigurationSection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Essentials warps: {@code plugins/Essentials/warps/*.yml}. A warp has no frame, so without an
 * explicit frame size every warp is reported as an Atlas-only entry instead of a guessed portal.
 */
public final class EssentialsWarpsImporter implements PortalImporter {
    private static final String FOLDER = "plugins/Essentials/warps";
    public static final String NO_FRAME = "no frame";

    private final int frameWidth;
    private final int frameHeight;

    public EssentialsWarpsImporter(int frameWidth, int frameHeight) {
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
    }

    @Override
    public String id() {
        return "essentials";
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
        List<Path> warps = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder)) {
            warps.addAll(files.filter(file -> file.getFileName().toString().endsWith(".yml")).sorted().toList());
        } catch (IOException unreadable) {
            report.skipped(folder.toString(), "unreadable: " + unreadable.getMessage());
            return report;
        }
        boolean framed = frameWidth > 0 && frameHeight > 0;
        for (Path warp : warps) {
            ConfigurationSection document = YamlDocuments.read(warp);
            String fileName = warp.getFileName().toString();
            String name = document == null ? fileName.substring(0, fileName.length() - 4)
                : YamlDocuments.stringAt(document, "name", fileName.substring(0, fileName.length() - 4));
            if (document == null) {
                report.skipped(name, "unreadable warp file");
                continue;
            }
            String world = YamlDocuments.stringAt(document, "world", "");
            if (world.isEmpty()) {
                report.skipped(name, "no world");
                continue;
            }
            if (!framed) {
                report.skipped(name, NO_FRAME);
                continue;
            }
            ImportedPortal imported = new ImportedPortal(name, world,
                (int) Math.floor(document.getDouble("x", 0.0D)),
                (int) Math.floor(document.getDouble("y", 0.0D)),
                (int) Math.floor(document.getDouble("z", 0.0D)),
                facing(document.getDouble("yaw", 0.0D)), frameWidth, frameHeight, "");
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
        if (!framed && report.skippedCount() > 0) {
            report.note("Warps carry no frame. Pass frame=<width>,<height> to build portals, "
                + "or keep them as Atlas entries.");
        }
        return report;
    }

    /** Minecraft yaw: 0 faces south, 90 faces west, 180 faces north, 270 faces east. */
    private static Direction facing(double yaw) {
        double normalized = ((yaw % 360.0D) + 360.0D) % 360.0D;
        if (normalized >= 45.0D && normalized < 135.0D) {
            return Direction.W;
        }
        if (normalized >= 135.0D && normalized < 225.0D) {
            return Direction.N;
        }
        if (normalized >= 225.0D && normalized < 315.0D) {
            return Direction.E;
        }
        return Direction.S;
    }
}
