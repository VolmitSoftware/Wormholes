package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.DistantHorizonsTap;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class DistantHorizonsPipelineTap {
    @Shadow
    @Final
    private RenderTargets renderTargets;

    @Inject(method = "beginLevelRendering", at = @At("TAIL"))
    private void wormholesTest$history(CallbackInfo callback) {
        RenderTarget history = renderTargets.get(5);
        if (history != null) {
            DistantHorizonsTap.history(this, history.getMainTexture(), history.getAltTexture(), SystemTimeUniforms.COUNTER.getAsInt());
        }
    }
}
