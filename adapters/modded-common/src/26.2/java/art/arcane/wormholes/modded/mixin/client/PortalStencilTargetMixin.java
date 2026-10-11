package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.stencil.PortalStencil;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.GpuFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(MainTarget.class)
public abstract class PortalStencilTargetMixin {
    @ModifyArg(method = "allocateDepthAttachment", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;"),
        index = 2)
    private static GpuFormat wormholes$stencilDepth(GpuFormat depth) {
        return PortalStencil.mainDepthFormat(depth);
    }
}
