package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.stencil.PortalStencil;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.mojang.blaze3d.opengl.GlDevice")
public abstract class PortalStencilTargetMixin {
    private static final String CREATE = "createTexture(Ljava/lang/String;ILcom/mojang/blaze3d/textures/TextureFormat;IIIIZ)Lcom/mojang/blaze3d/textures/GpuTexture;";

    @WrapOperation(method = CREATE, at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/opengl/GlConst;toGlInternalId(Lcom/mojang/blaze3d/textures/TextureFormat;Z)I"))
    private int wormholes$stencilInternal(TextureFormat format, boolean stencil, Operation<Integer> original) {
        return PortalStencil.internalFormat(format, original.call(format, stencil));
    }

    @WrapOperation(method = CREATE, at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/opengl/GlConst;toGlExternalId(Lcom/mojang/blaze3d/textures/TextureFormat;Z)I"))
    private int wormholes$stencilExternal(TextureFormat format, boolean stencil, Operation<Integer> original) {
        return PortalStencil.externalFormat(format, original.call(format, stencil));
    }

    @WrapOperation(method = CREATE, at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/opengl/GlConst;toGlType(Lcom/mojang/blaze3d/textures/TextureFormat;Z)I"))
    private int wormholes$stencilType(TextureFormat format, boolean stencil, Operation<Integer> original) {
        return PortalStencil.pixelType(format, original.call(format, stencil));
    }

    @Inject(method = CREATE, at = @At("RETURN"))
    private void wormholes$stencilCreated(String label, int usage, TextureFormat format, int width, int height, int depthOrLayers,
                                          int mipLevels, boolean stencil, CallbackInfoReturnable<GpuTexture> callback) {
        PortalStencil.created(callback.getReturnValue());
    }
}
