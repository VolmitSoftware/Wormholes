package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.iris.IrisViewPipelines;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.PipelineManager", remap = false)
public abstract class IrisPortalViewPipelineMixin {
    @Inject(method = "preparePipeline", at = @At("HEAD"), cancellable = true)
    private void wormholes$selectViewPipeline(NamespacedId dimension, CallbackInfoReturnable<WorldRenderingPipeline> callback) {
        IrisRenderingPipeline pipeline = IrisViewPipelines.select(dimension);
        if (pipeline != null) {
            ((IrisPortalPipelineAccess) this).wormholes$pipeline(pipeline);
            callback.setReturnValue(pipeline);
        }
    }

    @Inject(method = "destroyPipeline", at = @At("HEAD"))
    private void wormholes$releaseViewPipelines(CallbackInfo callback) {
        IrisViewPipelines.clear();
    }
}
