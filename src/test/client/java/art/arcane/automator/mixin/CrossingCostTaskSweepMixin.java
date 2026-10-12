package art.arcane.automator.mixin;

import art.arcane.automator.CrossingCosts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "art.arcane.wormholes.modded.client.render.sodium.SodiumTaskSweep", remap = false)
public abstract class CrossingCostTaskSweepMixin {
    @Inject(method = {"pending", "arrival"}, at = @At("HEAD"))
    private static void automator$pendingBegin(CallbackInfoReturnable<Object> callback) {
        CrossingCosts.begin(CrossingCosts.Phase.TASK_SWEEP);
    }

    @Inject(method = {"pending", "arrival"}, at = @At("RETURN"))
    private static void automator$pendingEnd(CallbackInfoReturnable<Object> callback) {
        CrossingCosts.end(CrossingCosts.Phase.TASK_SWEEP);
    }
}
