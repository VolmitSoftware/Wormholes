package art.arcane.automator.mixin;

import art.arcane.automator.CrossingCosts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "art.arcane.wormholes.modded.client.WormholesClient", remap = false)
public abstract class CrossingCostProjectedEntitiesMixin {
    @Inject(method = "dropProjectedEntities", at = @At("HEAD"))
    private void automator$dropProjectedEntitiesBegin(CallbackInfo callback) {
        CrossingCosts.begin(CrossingCosts.Phase.DROP_PROJECTED_ENTITIES);
    }

    @Inject(method = "dropProjectedEntities", at = @At("RETURN"))
    private void automator$dropProjectedEntitiesEnd(CallbackInfo callback) {
        CrossingCosts.end(CrossingCosts.Phase.DROP_PROJECTED_ENTITIES);
    }
}
