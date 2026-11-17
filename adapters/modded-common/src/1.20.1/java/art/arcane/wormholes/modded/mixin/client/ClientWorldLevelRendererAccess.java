package art.arcane.wormholes.modded.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.concurrent.BlockingQueue;

@Mixin(LevelRenderer.class)
public interface ClientWorldLevelRendererAccess {
    @Accessor("level")
    void wormholes$level(ClientLevel level);

    @Accessor("viewArea")
    ViewArea wormholes$viewArea();

    @Accessor("chunkRenderDispatcher")
    void wormholes$dispatcher(ChunkRenderDispatcher dispatcher);

    @Accessor("recentlyCompiledChunks")
    BlockingQueue<ChunkRenderDispatcher.RenderChunk> wormholes$recentlyCompiled();

    @Accessor("entityEffect")
    PostChain wormholes$entityEffect();

    @Accessor("entityEffect")
    void wormholes$entityEffect(PostChain effect);

    @Accessor("entityTarget")
    void wormholes$entityTarget(RenderTarget target);

    @Invoker("deinitTransparency")
    void wormholes$deinitTransparency();
}
