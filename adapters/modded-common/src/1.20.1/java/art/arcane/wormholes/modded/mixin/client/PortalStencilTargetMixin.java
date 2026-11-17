package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.stencil.PortalStencil;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gives every render target a stencil buffer; Forge's own enableStencil path is detected and left alone. */
@Mixin(RenderTarget.class)
public abstract class PortalStencilTargetMixin {
    @Inject(method = "createBuffers(IIZ)V", at = @At("TAIL"))
    private void wormholes$stencil(int width, int height, boolean checkError, CallbackInfo callback) {
        PortalStencil.ensure((RenderTarget) (Object) this);
    }

    @Inject(method = "destroyBuffers()V", at = @At("HEAD"))
    private void wormholes$forget(CallbackInfo callback) {
        PortalStencil.forget((RenderTarget) (Object) this);
    }
}
