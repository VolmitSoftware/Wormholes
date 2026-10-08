package art.arcane.wormholes.modded.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SkyRenderer.class)
public interface ClientWorldSkyAccess {
    @Accessor("renderTarget")
    RenderTarget wormholes$renderTarget();

    @Mutable
    @Accessor("renderTarget")
    void wormholes$renderTarget(RenderTarget target);
}
