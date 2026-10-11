package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalFeatureRenderer;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(RenderType.class)
public abstract class PortalFeaturePipelineMixin {
    @ModifyReturnValue(method = "prepare", at = @At("RETURN"))
    private PreparedRenderType wormholes$reflectedFeatures(PreparedRenderType prepared) {
        RenderPipeline pipeline = PortalFeatureRenderer.reflectedPipeline(prepared.pipeline());
        return pipeline == prepared.pipeline() ? prepared : new PreparedRenderType(pipeline, prepared.outputTarget(),
            prepared.dynamicTransforms(), prepared.scissorState(), prepared.textures());
    }
}
