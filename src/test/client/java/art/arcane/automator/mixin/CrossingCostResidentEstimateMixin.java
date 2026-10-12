package art.arcane.automator.mixin;

import art.arcane.automator.CrossingCosts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "art.arcane.wormholes.modded.client.ResidentLevel", remap = false)
public abstract class CrossingCostResidentEstimateMixin {
    @Inject(method = "estimate", at = @At("HEAD"))
    private static void automator$estimateBegin(CallbackInfoReturnable<Object> callback) {
        CrossingCosts.begin(CrossingCosts.Phase.RESIDENT_ESTIMATE);
    }

    @Inject(method = "estimate", at = @At("RETURN"))
    private static void automator$estimateEnd(CallbackInfoReturnable<Object> callback) {
        CrossingCosts.end(CrossingCosts.Phase.RESIDENT_ESTIMATE);
    }
}
