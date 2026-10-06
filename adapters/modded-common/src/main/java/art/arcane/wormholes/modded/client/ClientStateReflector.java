package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftProjectedBlockStates;
import art.arcane.optics.frame.DirectionMapping;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.aperture.ApertureDescriptor;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public final class ClientStateReflector {
    private final DirectionMapping[] mappings;
    private final Reference2ObjectOpenHashMap<BlockState, BlockState> cache;

    public ClientStateReflector(List<ApertureDescriptor> reflections) {
        this.mappings = new DirectionMapping[reflections.size()];
        for (int index = 0; index < mappings.length; index++) {
            ApertureDescriptor mirror = reflections.get(index);
            mappings[index] = DirectionMapping.mirror(mirror.frame(), mirror.mirrorQuarterTurns(), new double[3]);
        }
        this.cache = new Reference2ObjectOpenHashMap<>(64);
    }

    public ClientStateReflector(OpticTransform transform) {
        this.mappings = new DirectionMapping[] {DirectionMapping.axes(transform.permutation().x(), transform.permutation().y(), transform.permutation().z())};
        this.cache = new Reference2ObjectOpenHashMap<>(64);
    }

    public boolean identity() {
        return mappings.length == 0;
    }

    public BlockState reflect(BlockState state) {
        if (mappings.length == 0 || state.getProperties().isEmpty()) {
            return state;
        }
        BlockState cached = cache.get(state);
        if (cached != null) {
            return cached;
        }
        BlockState reflected = state;
        for (int index = 0; index < mappings.length; index++) {
            reflected = MinecraftProjectedBlockStates.transform(reflected, mappings[index]);
        }
        cache.put(state, reflected);
        return reflected;
    }
}
