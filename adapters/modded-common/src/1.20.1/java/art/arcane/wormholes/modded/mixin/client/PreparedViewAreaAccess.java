package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ViewArea.class)
public interface PreparedViewAreaAccess {
    @Invoker("getRenderChunkAt")
    ChunkRenderDispatcher.RenderChunk wormholes$section(BlockPos origin);
}
