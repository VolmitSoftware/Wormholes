package art.arcane.wormholes.modded;

import art.arcane.wormholes.render.DirectionMapping;
import art.arcane.wormholes.render.blockentity.BlockEntityMaterials;
import art.arcane.wormholes.render.ProjectionBlockTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.Locale;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

public enum MinecraftProjectorBlocks implements ProjectionBlockTypes<BlockState, BlockState> {
    INSTANCE;

    private static volatile BlockState occluded;

    @Override
    public BlockState air() {
        return Blocks.AIR.defaultBlockState();
    }

    @Override
    public BlockState occluded() {
        BlockState current = occluded;
        if (current != null) {
            return current;
        }
        synchronized (MinecraftProjectorBlocks.class) {
            if (occluded == null) {
                occluded = new BlockState(Blocks.STONE, new Property<?>[0], new Comparable<?>[0]);
            }
            return occluded;
        }
    }

    @Override
    public boolean isOccluded(BlockState block) {
        return block != null && block == occluded;
    }

    @Override
    public BlockState material(BlockState block) {
        return block;
    }

    @Override
    public String materialName(BlockState block) {
        return BuiltInRegistries.BLOCK.getKey(block.getBlock()).getPath().toUpperCase(Locale.ROOT);
    }

    @Override
    public boolean blockEntityCandidate(BlockState block) {
        return BlockEntityMaterials.isCandidate(materialName(block));
    }

    @Override
    public boolean isAir(BlockState block) {
        return block != null && block.isAir();
    }

    @Override
    public boolean isOccluding(BlockState block) {
        return block != null && !block.isAir() && block.canOcclude();
    }

    @Override
    public boolean requiresTransform(BlockState block) {
        return !block.getProperties().isEmpty();
    }

    @Override
    public BlockState transform(BlockState block, DirectionMapping mapping) {
        return MinecraftProjectedBlockStates.transform(block, mapping);
    }
}
