package art.arcane.wormholes.modded.mixin.client;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Vanilla terrain state that portal layers swap. The chunk list holds package-private {@code RenderChunkInfo}s. */
@Mixin(LevelRenderer.class)
public interface VanillaTerrainRendererAccess {
    @Accessor("renderChunksInFrustum")
    ObjectArrayList<?> wormholes$visibleChunks();

    @Mutable
    @Accessor("renderChunksInFrustum")
    void wormholes$visibleChunks(ObjectArrayList<?> chunks);

    @Accessor("viewArea")
    ViewArea wormholes$viewArea();

    @Accessor("chunkRenderDispatcher")
    ChunkRenderDispatcher wormholes$chunkDispatcher();

    @Accessor("xTransparentOld")
    double wormholes$transparentX();

    @Accessor("xTransparentOld")
    void wormholes$transparentX(double value);

    @Accessor("yTransparentOld")
    double wormholes$transparentY();

    @Accessor("yTransparentOld")
    void wormholes$transparentY(double value);

    @Accessor("zTransparentOld")
    double wormholes$transparentZ();

    @Accessor("zTransparentOld")
    void wormholes$transparentZ(double value);
}
