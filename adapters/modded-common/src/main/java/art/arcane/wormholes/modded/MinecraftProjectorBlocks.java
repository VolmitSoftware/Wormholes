package art.arcane.wormholes.modded;

import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.fidelity.BlockEntityMaterials;
import art.arcane.optics.state.StateProperties;
import art.arcane.optics.state.StateRewriteCache;
import art.arcane.optics.view.BlockStates;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

public enum MinecraftProjectorBlocks implements BlockStates<BlockState, BlockState> {
    INSTANCE;

    private static final Map<Block, Boolean> OCCLUDING = new ConcurrentHashMap<>();
    private static final StateRewriteCache<BlockState> REWRITES =
        new StateRewriteCache<BlockState>(MinecraftProjectorBlocks::readProperties, MinecraftProjectorBlocks::writeProperties);
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
        return !block.getProperties().isEmpty() && REWRITES.rewrites(block);
    }

    @Override
    public BlockState transform(BlockState block, AxisPermutation permutation) {
        return REWRITES.apply(block, permutation);
    }

    @Override
    public StateProperties properties(BlockState block) {
        return readProperties(block);
    }

    @Override
    public BlockState withProperties(BlockState block, StateProperties properties) {
        return writeProperties(block, properties);
    }

    private static StateProperties readProperties(BlockState state) {
        Collection<Property<?>> properties = state.getProperties();
        if (properties.isEmpty()) {
            return StateProperties.EMPTY;
        }
        Map<String, String> values = new HashMap<String, String>(properties.size() * 2);
        for (Property<?> property : properties) {
            values.put(property.getName(), valueName(state, property));
        }
        return StateProperties.of(values);
    }

    private static BlockState writeProperties(BlockState state, StateProperties properties) {
        StateDefinition<Block, BlockState> definition = state.getBlock().getStateDefinition();
        BlockState result = state;
        for (int index = 0; index < properties.size(); index++) {
            Property<?> property = definition.getProperty(properties.name(index));
            if (property != null) {
                result = withValue(result, property, properties.value(index));
            }
        }
        return result;
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String name) {
        Optional<T> value = property.getValue(name);
        return value.isPresent() ? state.setValue(property, value.get()) : state;
    }
}
