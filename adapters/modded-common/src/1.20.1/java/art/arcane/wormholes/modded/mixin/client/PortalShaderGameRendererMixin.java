package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Renderers that read the main camera during a portal layer get the layer camera. */
@Mixin(GameRenderer.class)
public abstract class PortalShaderGameRendererMixin {
    @Inject(method = "getMainCamera()Lnet/minecraft/client/Camera;", at = @At("HEAD"), cancellable = true)
    private void wormholes$layerCamera(CallbackInfoReturnable<Camera> callback) {
        Camera layer = PortalStencilRenderer.instance().camera();
        if (layer != null) {
            callback.setReturnValue(layer);
        }
    }
}
