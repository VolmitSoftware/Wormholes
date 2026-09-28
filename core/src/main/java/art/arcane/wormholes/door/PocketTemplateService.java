package art.arcane.wormholes.door;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Vanilla structure files used as pocket interiors.
 *
 * <p>A template only ever writes inside the room: the placement is anchored one block in from the
 * shell and the shell and its return door are re-laid afterwards. {@code paste} takes no
 * bounding box, so a template larger than the room cannot be trimmed to fit - it is refused, leaving
 * the room as it was, rather than written through the wall and into the pocket next door.</p>
 */
public class PocketTemplateService<S> {
    public static final String EXTENSION = ".nbt";

    private final Path dataFolder;
    private final Supplier<String> templatesDir;
    private final StructureIo<S> io;

    /** The folder is read per call so a hot reload moves the template folder without a restart. */
    public PocketTemplateService(Path dataFolder, Supplier<String> templatesDir, StructureIo<S> io) {
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder").toAbsolutePath().normalize();
        this.templatesDir = Objects.requireNonNull(templatesDir, "templatesDir");
        this.io = Objects.requireNonNull(io, "io");
    }

    public Path directory() {
        return dataFolder.resolve(Objects.requireNonNull(templatesDir.get(), "templatesDir"));
    }

    /** Template names, alphabetical, with the structure extension stripped. */
    public List<String> names() {
        Path directory = directory();
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(directory)) {
            List<String> names = new ArrayList<>();
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String fileName = path.getFileName().toString();
                if (fileName.endsWith(EXTENSION)) {
                    names.add(fileName.substring(0, fileName.length() - EXTENSION.length()));
                }
            }
            return List.copyOf(names);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not list pocket templates in " + directory, failure);
        }
    }

    public boolean exists(String name) {
        return Files.isRegularFile(file(name));
    }

    public Optional<S> load(String name) throws IOException {
        return io.load(file(name));
    }

    public Path file(String name) {
        String required = Objects.requireNonNull(name, "name").trim();
        if (required.isEmpty()) {
            throw new IllegalArgumentException("A pocket template name cannot be blank");
        }
        if (required.indexOf('/') >= 0 || required.indexOf('\\') >= 0 || required.contains("..")) {
            throw new IllegalArgumentException("A pocket template name cannot contain a path: " + name);
        }
        return directory().resolve(required + EXTENSION);
    }

    /** The buildable box one block inside the shell on every axis. */
    public static Placement interior(PocketLayout layout) {
        Objects.requireNonNull(layout, "layout");
        int size = layout.size() - 2;
        return new Placement(layout.minX() + 1, layout.minY() + 1, layout.minZ() + 1,
            Math.max(0, size), Math.max(0, size), Math.max(0, size));
    }

    /**
     * The box a structure occupies anchored at the interior corner, or nothing when it does not fit.
     * A structure is placed whole or not at all, so a template bigger than the room is refused.
     */
    public static Placement fit(PocketLayout layout, int structureX, int structureY, int structureZ) {
        Placement interior = interior(layout);
        boolean fits = structureX <= interior.sizeX() && structureY <= interior.sizeY() && structureZ <= interior.sizeZ();
        return new Placement(
            interior.originX(), interior.originY(), interior.originZ(),
            fits ? Math.max(0, structureX) : 0,
            fits ? Math.max(0, structureY) : 0,
            fits ? Math.max(0, structureZ) : 0);
    }

    /** An axis-aligned block box, anchored at its minimum corner. */
    public record Placement(int originX, int originY, int originZ, int sizeX, int sizeY, int sizeZ) {
        public Placement {
            if (sizeX < 0 || sizeY < 0 || sizeZ < 0) {
                throw new IllegalArgumentException("a placement cannot have a negative size");
            }
        }

        public boolean isEmpty() {
            return sizeX == 0 || sizeY == 0 || sizeZ == 0;
        }
    }
}
