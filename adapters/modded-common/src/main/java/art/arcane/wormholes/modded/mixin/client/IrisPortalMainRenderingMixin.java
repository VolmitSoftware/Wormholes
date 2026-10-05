package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalIrisMainPipelines;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisPortalMainRenderingMixin {
    @Inject(method = "beginLevelRendering", at = @At("RETURN"))
    private void wormholes$initializePrepared(CallbackInfo callback) {
        PortalIrisMainPipelines.begin((IrisRenderingPipeline) (Object) this);
    }
    @Inject(method = "finalizeLevelRendering", at = @At("RETURN"))
    private void wormholes$preparedDrawComplete(CallbackInfo callback) {
        PortalIrisMainPipelines.drawn((IrisRenderingPipeline) (Object) this);
    }
}
