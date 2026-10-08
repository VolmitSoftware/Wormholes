package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pathways.HandRenderer", remap = false)
public abstract class IrisPortalHandMixin {
    @Inject(method = "canRender", at = @At("HEAD"), cancellable = true)
    private void wormholes$noHandsInPortals(CallbackInfoReturnable<Boolean> callback) {
        if (PortalStencilRenderer.instance().nested()) {
            callback.setReturnValue(false);
        }
    }
}
