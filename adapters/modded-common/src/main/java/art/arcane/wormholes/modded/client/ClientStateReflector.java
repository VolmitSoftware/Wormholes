package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftProjectedBlockStates;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.render.DirectionMapping;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public final class ClientStateReflector {
    private final DirectionMapping[] mappings;
    private final Reference2ObjectOpenHashMap<BlockState, BlockState> cache;

    public ClientStateReflector(List<ClientPortalGeometry> reflections) {
        this.mappings = new DirectionMapping[reflections.size()];
        for (int index = 0; index < mappings.length; index++) {
            ClientPortalGeometry mirror = reflections.get(index);
            mappings[index] = DirectionMapping.mirror(mirror.frame(), mirror.mirrorQuarterTurns(), new double[3]);
        }
        this.cache = new Reference2ObjectOpenHashMap<>(64);
    }

    public ClientStateReflector(ClientViewEnvironment.Transform transform) {
        this.mappings = new DirectionMapping[] {DirectionMapping.axes(transform.xAxis(), transform.yAxis(), transform.zAxis())};
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
