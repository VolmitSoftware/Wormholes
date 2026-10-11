package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.stencil.PortalStencil;
import com.mojang.blaze3d.opengl.DirectStateAccess;
import com.mojang.blaze3d.opengl.FrameBufferAttachment;
import com.mojang.blaze3d.opengl.FrameBufferCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(FrameBufferCache.class)
public abstract class PortalStencilAttachmentMixin {
    @Inject(method = "createFbo", at = @At("RETURN"))
    private void wormholes$attachStencil(FrameBufferCache.CacheKey key, DirectStateAccess access, List<FrameBufferAttachment> colors,
                                        FrameBufferAttachment depth, CallbackInfoReturnable<Integer> callback) {
        PortalStencil.attach(callback.getReturnValueI(), depth);
    }
}
