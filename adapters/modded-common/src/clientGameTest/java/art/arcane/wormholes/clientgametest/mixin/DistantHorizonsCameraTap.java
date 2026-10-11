package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.DistantHorizonsTap;
import art.arcane.wormholes.modded.client.render.stencil.PortalLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PortalLayer.class, remap = false)
public abstract class DistantHorizonsCameraTap {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void wormholesTest$camera(CallbackInfo callback) {
        DistantHorizonsTap.layer((PortalLayer) (Object) this);
    }
}
