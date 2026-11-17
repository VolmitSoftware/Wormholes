package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.stencil.PortalStencil;
import com.mojang.blaze3d.pipeline.MainTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The main target's first allocation bypasses {@code createBuffers}, so it is upgraded here. */
@Mixin(MainTarget.class)
public abstract class PortalStencilMainTargetMixin {
    @Inject(method = "createFrameBuffer(II)V", at = @At("TAIL"))
    private void wormholes$stencil(int width, int height, CallbackInfo callback) {
        PortalStencil.ensure((MainTarget) (Object) this);
    }
}
