package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameRenderer.class)
public interface ClientWorldGameRendererAccess {
    @Accessor("lightmap")
    Lightmap wormholes$lightmap();

    @Mutable
    @Accessor("lightmap")
    void wormholes$lightmap(Lightmap lightmap);

    @Accessor("lightmapRenderStateExtractor")
    LightmapRenderStateExtractor wormholes$lightmapExtractor();
}
