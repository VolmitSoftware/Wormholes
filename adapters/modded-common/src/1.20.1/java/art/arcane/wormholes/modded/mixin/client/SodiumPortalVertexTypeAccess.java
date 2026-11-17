package art.arcane.wormholes.modded.mixin.client;

import me.jellysquid.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(value = ShaderChunkRenderer.class, remap = false)
public interface SodiumPortalVertexTypeAccess {
    @Accessor("vertexType")
    ChunkVertexType wormholes$vertexType();
}
