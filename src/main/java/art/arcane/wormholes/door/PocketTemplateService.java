package art.arcane.wormholes.door;

import art.arcane.wormholes.Wormholes;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.structure.Structure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Vanilla structure files used as pocket interiors.
 *
 * <p>A template only ever writes inside the room: the placement is anchored one block in from the
 * shell and the shell and its return door are re-laid afterwards. {@code Structure.place} takes no
 * bounding box, so a template larger than the room cannot be trimmed to fit - it is refused, leaving
 * the room as it was, rather than written through the wall and into the pocket next door.</p>
 */
public final class PocketTemplateService {
    public static final String EXTENSION = ".nbt";

    private final Path dataFolder;
    private final Supplier<String> templatesDir;
    private final StructureIo io;

    /** The folder is read per call so a hot reload moves the template folder without a restart. */
    public PocketTemplateService(Path dataFolder, Supplier<String> templatesDir, StructureIo io) {
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder").toAbsolutePath().normalize();
        this.templatesDir = Objects.requireNonNull(templatesDir, "templatesDir");
        this.io = Objects.requireNonNull(io, "io");
    }

    /** The live service, reading the configured folder through the server's own structure loader. */
    public static PocketTemplateService under(Path dataFolder) {
        return new PocketTemplateService(
            dataFolder, () -> PocketSettings.current().templatesDir, StructureIo.server());
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

    public Optional<Structure> load(String name) throws IOException {
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

    /**
     * Stamps a template into one pocket on the owning region thread.
     *
     * @return the placement that was written, empty when the room has no interior at all
     */
    public Placement paste(World world, PocketSpace space, Structure structure, boolean clearFirst) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(space, "space");
        Objects.requireNonNull(structure, "structure");
        PocketLayout layout = new PocketLayout(space);
        PocketStructureService.requireWorldHeight(world, layout);
        PocketStructureService.requireRegionOwnership(world, layout);
        int structureX = structure.getSize().getBlockX();
        int structureY = structure.getSize().getBlockY();
        int structureZ = structure.getSize().getBlockZ();
        Placement placement = fit(layout, structureX, structureY, structureZ);
        if (placement.isEmpty() && structureX > 0 && structureY > 0 && structureZ > 0) {
            Placement interior = interior(layout);
            Wormholes.w("pocket template " + structureX + "x" + structureY + "x" + structureZ
                + " does not fit the " + interior.sizeX() + "x" + interior.sizeY() + "x" + interior.sizeZ()
                + " interior of pocket " + space.spaceId() + "; it was not placed");
            return placement;
        }
        if (clearFirst) {
            clearInterior(world, layout);
        }
        if (!placement.isEmpty()) {
            structure.place(
                new Location(world, placement.originX(), placement.originY(), placement.originZ()),
                true, StructureRotation.NONE, Mirror.NONE, 0, 1.0F, new Random(space.spaceId().hashCode()));
        }
        // The template may have written over the walls; the shell is the pocket's only invariant.
        PocketStructureService.initializeShell(world, layout, PocketMaterials.shellMaterialOrDefault(
            space.shell().shellMaterial()));
        PocketStructureService.repairReturnDoor(world, layout,
            PocketMaterials.shellMaterialOrDefault(space.shell().shellMaterial()),
            PocketMaterials.returnDoorMaterialOrDefault(space.shell().returnDoorMaterial()));
        return placement;
    }

    /** Copies the room interior, shell excluded, for a snapshot. */
    public Structure capture(World world, PocketSpace space) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(space, "space");
        PocketLayout layout = new PocketLayout(space);
        PocketStructureService.requireRegionOwnership(world, layout);
        Placement interior = interior(layout);
        Structure structure = Bukkit.getStructureManager().createStructure();
        structure.fill(
            new Location(world, interior.originX(), interior.originY(), interior.originZ()),
            new Location(world,
                interior.originX() + interior.sizeX() - 1,
                interior.originY() + interior.sizeY() - 1,
                interior.originZ() + interior.sizeZ() - 1),
            true);
        return structure;
    }

    public static void clearInterior(World world, PocketLayout layout) {
        Placement interior = interior(layout);
        for (int x = 0; x < interior.sizeX(); x++) {
            for (int y = 0; y < interior.sizeY(); y++) {
                for (int z = 0; z < interior.sizeZ(); z++) {
                    PocketStructureService.setType(world.getBlockAt(
                        interior.originX() + x, interior.originY() + y, interior.originZ() + z), Material.AIR);
                }
            }
        }
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
