package art.arcane.automator.mixin;

import art.arcane.automator.CrossingCosts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.PipelineManager", remap = false)
public abstract class CrossingCostIrisPrepareMixin {
    @Inject(method = "preparePipeline", at = @At("HEAD"))
    private void automator$preparePipelineBegin(CallbackInfoReturnable<Object> callback) {
        CrossingCosts.begin(CrossingCosts.irisPreparePhase());
    }

    @Inject(method = "preparePipeline", at = @At("RETURN"))
    private void automator$preparePipelineEnd(CallbackInfoReturnable<Object> callback) {
        CrossingCosts.end(CrossingCosts.irisPreparePhase());
    }
}
