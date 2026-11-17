package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.client.render.stencil.PortalFrameHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the streamed portal views over the shaded image once Iris's composite and final passes have written it. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisPortalCompositeMixin {
    @Inject(method = "finalizeLevelRendering()V", at = @At("RETURN"))
    private void wormholes$composeDestinationImages(CallbackInfo callback) {
        if (!PortalFrameHooks.inLayer()) {
            ClientPortalRenderer.instance().compositeAfterShaders();
        }
    }
}
