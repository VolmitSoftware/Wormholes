package art.arcane.wormholes.modded;

import art.arcane.wormholes.door.PocketLayout;
import art.arcane.wormholes.door.PocketSpace;
import art.arcane.wormholes.door.PocketTemplateService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.nio.file.Path;
import java.util.List;

final class MinecraftPocketTemplates extends PocketTemplateService<StructureTemplate> {
    MinecraftPocketTemplates(Path directory, WormholesModConfiguration configuration, MinecraftStructureIo io) {
        super(directory, () -> configuration.settings().getPockets().templatesDir, io);
    }

    Placement validate(PocketSpace space, StructureTemplate structure) {
        Vec3i size = structure.getSize();
        Placement placement = fit(new PocketLayout(space), size.getX(), size.getY(), size.getZ());
        if (placement.isEmpty() && size.getX() > 0 && size.getY() > 0 && size.getZ() > 0) {
            throw new IllegalArgumentException("Template does not fit the pocket interior");
        }
        return placement;
    }

    void paste(ServerLevel level, PocketSpace space, StructureTemplate structure) {
        Placement placement = validate(space, structure);
        if (placement.isEmpty()) {
            return;
        }
        BlockPos origin = new BlockPos(placement.originX(), placement.originY(), placement.originZ());
        StructurePlaceSettings settings = new StructurePlaceSettings().setBoundingBox(new BoundingBox(
            placement.originX(), placement.originY(), placement.originZ(),
            placement.originX() + placement.sizeX() - 1, placement.originY() + placement.sizeY() - 1,
            placement.originZ() + placement.sizeZ() - 1));
        if (!structure.placeInWorld(level, origin, origin, settings, RandomSource.create(space.spaceId().hashCode()), Block.UPDATE_CLIENTS)) {
            throw new IllegalStateException("Pocket structure placement failed for " + space.spaceId());
        }
    }

    StructureTemplate capture(ServerLevel level, PocketSpace space) {
        Placement interior = interior(new PocketLayout(space));
        StructureTemplate structure = new StructureTemplate();
        structure.fillFromWorld(level, new BlockPos(interior.originX(), interior.originY(), interior.originZ()),
            new Vec3i(interior.sizeX(), interior.sizeY(), interior.sizeZ()), true, List.of());
        return structure;
    }
}
