package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.dh.DhBlazeClipTransformation;
import art.arcane.wormholes.modded.client.render.stencil.PortalClipShaders;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPipelineBuilderWrapper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPipelineBuilderWrapper", remap = false)
public abstract class DhPortalBlazePipelineMixin {
    @Shadow
    @Final
    private RenderPipeline.Builder blazePipelineBuilder;

    @Unique
    private boolean wormholes$clipDeclared;

    @Inject(method = "withVertexShader", at = @At("RETURN"))
    private void wormholes$clipLayout(String shader, CallbackInfoReturnable<RenderPipelineBuilderWrapper> callback) {
        if (!wormholes$clipDeclared && DhBlazeClipTransformation.owns(shader) && PortalClipShaders.openGl()) {
            wormholes$clipDeclared = true;
            blazePipelineBuilder.withUniform(DhBlazeClipTransformation.BLOCK, UniformType.UNIFORM_BUFFER);
        }
    }
}
