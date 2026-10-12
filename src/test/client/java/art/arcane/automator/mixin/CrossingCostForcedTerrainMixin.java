package art.arcane.automator.mixin;

import art.arcane.automator.CrossingCosts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "art.arcane.wormholes.modded.client.render.sodium.SodiumSectionDiscovery", remap = false)
public abstract class CrossingCostForcedTerrainMixin {
    @Inject(method = "terrainReady", at = @At("HEAD"))
    private static void automator$terrainReadyBegin(CallbackInfo callback) {
        CrossingCosts.begin(CrossingCosts.Phase.FORCED_TERRAIN);
    }

    @Inject(method = "terrainReady", at = @At("RETURN"))
    private static void automator$terrainReadyEnd(CallbackInfo callback) {
        CrossingCosts.end(CrossingCosts.Phase.FORCED_TERRAIN);
    }
}
