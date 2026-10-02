package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalIrisShaderStages;
import art.arcane.wormholes.modded.client.render.PortalShaderContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.PipelineManager", remap = false)
public abstract class IrisPortalShaderStagesLifecycleMixin {
    @Inject(method = "destroyPipeline", at = @At("HEAD"))
    private void wormholes$clear(CallbackInfo callback) {
        if (PortalShaderContext.current() == null) {
            PortalIrisShaderStages.clearCurrentContext();
        }
    }
}
