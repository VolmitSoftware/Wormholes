package art.arcane.wormholes.door;

import art.arcane.wormholes.Wormholes;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.structure.Structure;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Random;
import java.util.function.Supplier;

public final class BukkitPocketTemplates extends PocketTemplateService<Structure> {
    public BukkitPocketTemplates(Path dataFolder, Supplier<String> templatesDir, StructureIo<Structure> io) {
        super(dataFolder, templatesDir, io);
    }

    public static BukkitPocketTemplates under(Path dataFolder) {
        return new BukkitPocketTemplates(dataFolder, () -> PocketSettings.current().templatesDir, BukkitStructureIo.INSTANCE);
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

}
