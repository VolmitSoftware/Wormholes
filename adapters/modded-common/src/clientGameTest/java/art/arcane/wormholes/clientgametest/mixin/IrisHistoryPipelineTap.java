package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.IrisHistoryTap;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisHistoryPipelineTap {
    @Shadow
    @Final
    private RenderTargets renderTargets;

    @Inject(method = "beginLevelRendering", at = @At("TAIL"))
    private void wormholesTest$history(CallbackInfo callback) {
        IrisHistoryTap.rendered((IrisRenderingPipeline) (Object) this, renderTargets);
    }
}
