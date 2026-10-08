package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.IrisHistoryTap;
import art.arcane.wormholes.modded.client.render.iris.IrisPipelineBackend;
import art.arcane.wormholes.modded.client.render.stencil.PortalLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = IrisPipelineBackend.class, remap = false)
public abstract class IrisHistoryLayerTap {
    @Inject(method = "beginLayer", at = @At("HEAD"))
    private void wormholesTest$enter(PortalLayer layer, CallbackInfo callback) {
        IrisHistoryTap.enter(layer);
    }

    @Inject(method = "endLayer", at = @At("TAIL"))
    private void wormholesTest$leave(PortalLayer layer, CallbackInfo callback) {
        IrisHistoryTap.leave();
    }
}
