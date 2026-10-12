package art.arcane.automator.mixin;

import art.arcane.automator.CrossingCosts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class CrossingCostIrisBeginMixin {
    @Inject(method = "beginLevelRendering", at = @At("HEAD"))
    private void automator$beginLevelRenderingBegin(CallbackInfo callback) {
        CrossingCosts.begin(CrossingCosts.irisBeginPhase());
    }

    @Inject(method = "beginLevelRendering", at = @At("RETURN"))
    private void automator$beginLevelRenderingEnd(CallbackInfo callback) {
        CrossingCosts.end(CrossingCosts.irisBeginPhase());
    }
}
