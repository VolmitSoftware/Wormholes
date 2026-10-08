package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class PortalShaderGameRendererMixin {
    @Inject(method = "mainCamera", at = @At("HEAD"), cancellable = true)
    private void wormholes$destinationCamera(CallbackInfoReturnable<Camera> callback) {
        Camera portal = PortalStencilRenderer.instance().camera();
        if (portal != null) {
            callback.setReturnValue(portal);
        }
    }
}
