package art.arcane.wormholes.modded;

import art.arcane.optics.frame.DirectionMapping;
import art.arcane.optics.fidelity.BlockEntityMaterials;
import art.arcane.optics.view.BlockStates;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

public enum MinecraftProjectorBlocks implements BlockStates<BlockState, BlockState> {
    INSTANCE;

    private static final Map<Block, Boolean> OCCLUDING = new ConcurrentHashMap<>();
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
        return block != null && !block.isAir() && occluding(block.getBlock());
    }

    static boolean occluding(Block block) {
        Boolean cached = OCCLUDING.get(block);
        if (cached != null) {
            return cached;
        }
        boolean occluding = block.defaultBlockState().isRedstoneConductor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        OCCLUDING.put(block, occluding);
        return occluding;
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
