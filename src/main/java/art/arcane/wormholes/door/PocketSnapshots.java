package art.arcane.wormholes.door;

import org.bukkit.structure.Structure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Operator-taken copies of one pocket's interior, kept per pocket under
 * {@code <data>/pockets/snapshots/<space>/<name>.nbt}.
 */
public final class PocketSnapshots {
    public static final String LATEST = "latest";
    public static final String FOLDER = "pockets/snapshots";

    private final Path dataFolder;
    private final StructureIo io;

    public PocketSnapshots(Path dataFolder, StructureIo io) {
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder").toAbsolutePath().normalize();
        this.io = Objects.requireNonNull(io, "io");
    }

    public Path directory(UUID spaceId) {
        return dataFolder.resolve(FOLDER).resolve(Objects.requireNonNull(spaceId, "spaceId").toString());
    }

    public Path file(UUID spaceId, String name) {
        String required = Objects.requireNonNull(name, "name").trim();
        if (required.isEmpty()) {
            throw new IllegalArgumentException("A snapshot name cannot be blank");
        }
        if (required.indexOf('/') >= 0 || required.indexOf('\\') >= 0 || required.contains("..")) {
            throw new IllegalArgumentException("A snapshot name cannot contain a path: " + name);
        }
        return directory(spaceId).resolve(required + PocketTemplateService.EXTENSION);
    }

    public List<String> names(UUID spaceId) {
        Path directory = directory(spaceId);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(directory)) {
            List<String> names = new ArrayList<>();
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String fileName = path.getFileName().toString();
                if (fileName.endsWith(PocketTemplateService.EXTENSION)) {
                    names.add(fileName.substring(
                        0, fileName.length() - PocketTemplateService.EXTENSION.length()));
                }
            }
            return List.copyOf(names);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not list pocket snapshots in " + directory, failure);
        }
    }

    public void save(UUID spaceId, String name, Structure structure) throws IOException {
        Objects.requireNonNull(structure, "structure");
        io.save(structure, file(spaceId, name));
    }

    public Optional<Structure> load(UUID spaceId, String name) throws IOException {
        return io.load(file(spaceId, name));
    }
}
