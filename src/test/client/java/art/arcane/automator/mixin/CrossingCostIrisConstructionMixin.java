package art.arcane.automator.mixin;

import art.arcane.automator.CrossingCosts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class CrossingCostIrisConstructionMixin {
    @Inject(method = "<init>", at = @At(value = "CTOR_HEAD", args = "enforce=POST_DELEGATE", unsafe = true))
    private void automator$constructBegin(CallbackInfo callback) {
        CrossingCosts.begin(CrossingCosts.Phase.IRIS_CONSTRUCT);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void automator$constructEnd(CallbackInfo callback) {
        CrossingCosts.end(CrossingCosts.Phase.IRIS_CONSTRUCT);
    }
}
