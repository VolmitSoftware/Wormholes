package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.dh.DhPortalRendering;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.api.internal.ClientApi", remap = false)
public abstract class DhPortalRenderBuffersMixin {
    @Inject(method = "renderLodLayer", at = @At(value = "INVOKE",
        target = "Lcom/seibel/distanthorizons/core/render/RenderParams;getValidationErrorMessage()Ljava/lang/String;"))
    private void wormholes$portalBuffers(boolean deferred, CallbackInfo callback) {
        DhPortalRendering.prepareRenderParams();
    }
}
