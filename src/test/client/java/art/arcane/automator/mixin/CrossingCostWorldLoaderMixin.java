package art.arcane.automator.mixin;

import art.arcane.automator.CrossingCosts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "art.arcane.wormholes.modded.client.world.ClientWorldLoader", remap = false)
public abstract class CrossingCostWorldLoaderMixin {
    @Inject(method = "prepareArrivalTerrain", at = @At("HEAD"))
    private static void automator$arrivalBegin(CallbackInfo callback) {
        CrossingCosts.begin(CrossingCosts.Phase.ARRIVAL_PREPARE);
    }

    @Inject(method = "prepareArrivalTerrain", at = @At("RETURN"))
    private static void automator$arrivalEnd(CallbackInfo callback) {
        CrossingCosts.end(CrossingCosts.Phase.ARRIVAL_PREPARE);
    }

    @Inject(method = "changeLevel", at = @At("HEAD"))
    private static void automator$changeLevelBegin(CallbackInfo callback) {
        CrossingCosts.begin(CrossingCosts.Phase.CHANGE_LEVEL);
    }

    @Inject(method = "changeLevel", at = @At("RETURN"))
    private static void automator$changeLevelEnd(CallbackInfo callback) {
        CrossingCosts.end(CrossingCosts.Phase.CHANGE_LEVEL);
    }

    @Inject(method = "beforeExtract", at = @At("HEAD"))
    private static void automator$beforeExtractBegin(CallbackInfo callback) {
        CrossingCosts.begin(CrossingCosts.Phase.BEFORE_EXTRACT);
    }

    @Inject(method = "beforeExtract", at = @At("RETURN"))
    private static void automator$beforeExtractEnd(CallbackInfo callback) {
        CrossingCosts.end(CrossingCosts.Phase.BEFORE_EXTRACT);
    }
}
