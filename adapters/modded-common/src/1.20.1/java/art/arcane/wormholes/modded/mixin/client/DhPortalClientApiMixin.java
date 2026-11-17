package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.dh.DhLodPasses;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every Distant Horizons draw on both loaders funnels through these four {@link ClientApi} methods. */
@Mixin(value = ClientApi.class, remap = false)
public abstract class DhPortalClientApiMixin {
    @Inject(method = "renderLods()V", at = @At("HEAD"), cancellable = true)
    private void wormholes$lods(CallbackInfo callback) {
        if (DhLodPasses.beginLods()) {
            callback.cancel();
        }
    }

    @Inject(method = {"renderDeferredLodsForShaders()V", "renderFadeOpaque()V", "renderFadeTransparent()V"}, at = @At("HEAD"),
        cancellable = true)
    private void wormholes$pass(CallbackInfo callback) {
        if (DhLodPasses.beginPass()) {
            callback.cancel();
        }
    }

    @Inject(method = {"renderLods()V", "renderDeferredLodsForShaders()V", "renderFadeOpaque()V", "renderFadeTransparent()V"},
        at = @At("RETURN"))
    private void wormholes$passDone(CallbackInfo callback) {
        DhLodPasses.endPass();
    }
}
