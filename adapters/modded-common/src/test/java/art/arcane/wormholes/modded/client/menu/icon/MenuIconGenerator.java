package art.arcane.wormholes.modded.client.menu.icon;

import art.arcane.wormholes.modded.client.menu.icon.MenuIconMaps.MenuIconMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

public final class MenuIconGenerator {
    public static final Path ICON_DIRECTORY = Path.of("adapters", "modded-common", "src", "main", "resources", "assets", "wormholes", "textures", "gui", "sprites", "icon");
    public static final String COMMAND = "java adapters/modded-common/src/test/java/art/arcane/wormholes/modded/client/menu/icon/MenuIconGenerator.java";

    private MenuIconGenerator() {
    }

    public static void main(String[] args) throws IOException {
        Path directory = args.length > 0 ? Path.of(args[0]) : iconDirectory();
        write(directory);
        System.out.println("Wrote " + MenuIconMaps.all().size() + " icons to " + directory.toAbsolutePath());
    }

    public static Path iconDirectory() {
        Path start = Path.of("").toAbsolutePath();
        Path cursor = start;
        while (cursor != null) {
            if (Files.isDirectory(cursor.resolve("adapters").resolve("modded-common"))) {
                return cursor.resolve(ICON_DIRECTORY);
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("Wormholes repository root not found above " + start);
    }

    public static void write(Path directory) throws IOException {
        Files.createDirectories(directory);
        Set<Path> written = new HashSet<>();
        for (MenuIconMap map : MenuIconMaps.all()) {
            Path file = directory.resolve(map.name() + ".png");
            Files.createDirectories(file.getParent());
            if (!ImageIO.write(map.render(), "png", file.toFile())) {
                throw new IOException("No PNG writer available for " + map.name());
            }
            written.add(file);
        }
        try (Stream<Path> files = Files.walk(directory)) {
            List<Path> stale = files.filter(file -> isStalePng(file, written)).toList();
            for (Path file : stale) {
                Files.delete(file);
            }
        }
    }

    private static boolean isStalePng(Path file, Set<Path> written) {
        return Files.isRegularFile(file) && file.getFileName().toString().endsWith(".png") && !written.contains(file);
    }
}
