package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.dh.DhPortalRendering;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = {"com.seibel.distanthorizons.core.world.DhClientServerWorld$1",
    "com.seibel.distanthorizons.core.world.DhClientWorld$1"}, remap = false)
public abstract class DhPortalTickMixin {
    @Inject(method = "run", at = @At("RETURN"))
    private void wormholes$projectedTerrain(CallbackInfo callback) {
        DhPortalRendering.tickViews();
    }
}
