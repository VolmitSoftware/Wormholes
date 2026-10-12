package art.arcane.automator.mixin;

import art.arcane.automator.CrossingCosts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "art.arcane.wormholes.modded.client.ResidentLevels", remap = false)
public abstract class CrossingCostResidentsMixin {
    @Inject(method = "retire", at = @At("HEAD"))
    private void automator$retireBegin(CallbackInfo callback) {
        CrossingCosts.begin(CrossingCosts.Phase.RETIRE);
    }

    @Inject(method = "retire", at = @At("RETURN"))
    private void automator$retireEnd(CallbackInfo callback) {
        CrossingCosts.end(CrossingCosts.Phase.RETIRE);
    }

    @Inject(method = "enforceBudget", at = @At("HEAD"))
    private void automator$enforceBudgetBegin(CallbackInfo callback) {
        CrossingCosts.begin(CrossingCosts.Phase.RESIDENT_BUDGET);
    }

    @Inject(method = "enforceBudget", at = @At("RETURN"))
    private void automator$enforceBudgetEnd(CallbackInfo callback) {
        CrossingCosts.end(CrossingCosts.Phase.RESIDENT_BUDGET);
    }
}
