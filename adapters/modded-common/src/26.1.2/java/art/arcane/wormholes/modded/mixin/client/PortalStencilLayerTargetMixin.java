package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.stencil.PortalStencil;
import com.mojang.blaze3d.opengl.GlTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlTexture.class)
public abstract class PortalStencilLayerTargetMixin {
    @Shadow @Final protected int id;

    @Inject(method = "destroyImmediately", at = @At("HEAD"))
    private void wormholes$forgetStencil(CallbackInfo callback) {
        PortalStencil.destroyed(id);
    }
}
