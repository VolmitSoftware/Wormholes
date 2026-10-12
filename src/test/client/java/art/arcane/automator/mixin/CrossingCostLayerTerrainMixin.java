package art.arcane.automator.mixin;

import art.arcane.automator.CrossingCosts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "art.arcane.wormholes.modded.client.render.sodium.SodiumLayerTerrain", remap = false)
public abstract class CrossingCostLayerTerrainMixin {
    @Inject(method = "collect", at = @At("HEAD"))
    private static void automator$collectBegin(CallbackInfo callback) {
        CrossingCosts.begin(CrossingCosts.Phase.COLLECT_TERRAIN);
    }

    @Inject(method = "collect", at = @At("RETURN"))
    private static void automator$collectEnd(CallbackInfo callback) {
        CrossingCosts.end(CrossingCosts.Phase.COLLECT_TERRAIN);
    }

    @Inject(method = "build", at = @At("HEAD"))
    private static void automator$buildBegin(@Coerce Object world, @Coerce Object viewport, @Coerce Object origin,
                                           boolean arrival, CallbackInfo callback) {
        CrossingCosts.begin(arrival ? CrossingCosts.Phase.ARRIVAL_BUILD : CrossingCosts.Phase.PORTAL_BUILD);
    }

    @Inject(method = "build", at = @At("RETURN"))
    private static void automator$buildEnd(@Coerce Object world, @Coerce Object viewport, @Coerce Object origin,
                                         boolean arrival, CallbackInfo callback) {
        CrossingCosts.end(arrival ? CrossingCosts.Phase.ARRIVAL_BUILD : CrossingCosts.Phase.PORTAL_BUILD);
    }
}
