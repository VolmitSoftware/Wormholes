package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.stencil.PortalStencil;
import com.mojang.blaze3d.opengl.DirectStateAccess;
import com.mojang.blaze3d.opengl.GlTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GlTexture.class)
public abstract class PortalStencilAttachmentMixin {
    @Inject(method = "createFbo", at = @At("RETURN"))
    private void wormholes$attachStencil(DirectStateAccess access, int depth, CallbackInfoReturnable<Integer> callback) {
        PortalStencil.attach(callback.getReturnValueI(), depth);
    }
}
