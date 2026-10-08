package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.stencil.StencilPipelines;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;

@Mixin(RenderPipelines.class)
public abstract class PortalStencilPipelinesMixin {
    @ModifyReturnValue(method = "optionalPipelines", at = @At("RETURN"))
    private static List<RenderPipeline> wormholes$stencilPipelines(List<RenderPipeline> pipelines) {
        List<RenderPipeline> all = new ArrayList<>(pipelines.size() + StencilPipelines.ALL.size());
        all.addAll(pipelines);
        all.addAll(StencilPipelines.ALL);
        return all;
    }
}
