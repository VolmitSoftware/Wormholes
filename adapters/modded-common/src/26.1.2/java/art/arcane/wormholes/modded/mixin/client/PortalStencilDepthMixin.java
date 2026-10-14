package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.stencil.PortalStencil;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(RenderTarget.class)
public abstract class PortalStencilDepthMixin {
    @Shadow @Final protected String label;

    @WrapMethod(method = "createBuffers")
    private void wormholes$stencilDepth(int width, int height, Operation<Void> original) {
        PortalStencil.createTarget(PortalStencil.stencilTarget((RenderTarget) (Object) this, label), () -> original.call(width, height));
    }

    @Mixin(MainTarget.class)
    public abstract static class Main {
        @WrapMethod(method = "createFrameBuffer")
        private void wormholes$stencilDepth(int width, int height, Operation<Void> original) {
            PortalStencil.createTarget(true, () -> original.call(width, height));
        }
    }
}
