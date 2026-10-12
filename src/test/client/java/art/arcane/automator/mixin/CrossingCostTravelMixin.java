package art.arcane.automator.mixin;

import art.arcane.automator.CrossingCosts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "art.arcane.wormholes.modded.client.ClientSeamlessTravel", remap = false)
public abstract class CrossingCostTravelMixin {
    @Inject(method = "cross", at = @At("HEAD"))
    private void automator$crossBegin(CallbackInfoReturnable<Object> callback) {
        CrossingCosts.begin(CrossingCosts.Phase.CROSSING_ATTEMPT);
    }

    @Inject(method = "cross", at = @At("RETURN"))
    private void automator$crossEnd(CallbackInfoReturnable<Object> callback) {
        CrossingCosts.end(CrossingCosts.Phase.CROSSING_ATTEMPT);
    }

    @Inject(method = "confirmed", at = @At("HEAD"))
    private void automator$confirmedBegin(CallbackInfo callback) {
        CrossingCosts.begin(CrossingCosts.Phase.ACCEPT);
    }

    @Inject(method = "confirmed", at = @At("RETURN"))
    private void automator$confirmedEnd(CallbackInfo callback) {
        CrossingCosts.end(CrossingCosts.Phase.ACCEPT);
    }

    @Inject(method = "serverCrossing", at = @At("HEAD"))
    private void automator$serverCrossingBegin(CallbackInfo callback) {
        CrossingCosts.begin(CrossingCosts.Phase.SERVER_CROSSING);
    }

    @Inject(method = "serverCrossing", at = @At("RETURN"))
    private void automator$serverCrossingEnd(CallbackInfo callback) {
        CrossingCosts.end(CrossingCosts.Phase.SERVER_CROSSING);
    }
}
