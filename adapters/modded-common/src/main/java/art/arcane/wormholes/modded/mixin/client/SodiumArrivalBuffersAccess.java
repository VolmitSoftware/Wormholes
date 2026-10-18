package art.arcane.wormholes.modded.mixin.client;

import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(value = ChunkBuildBuffers.class, remap = false)
public interface SodiumArrivalBuffersAccess {
    @Accessor("vertexType")
    ChunkVertexType wormholes$vertexType();
}
