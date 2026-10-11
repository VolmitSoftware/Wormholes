package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.stencil.PortalStencil;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(RenderTarget.class)
public abstract class PortalStencilLayerTargetMixin {
    @Shadow @Final protected String label;

    @ModifyArg(method = "createBuffers", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;",
        ordinal = 0), index = 2)
    private GpuFormat wormholes$layerStencil(GpuFormat depth) {
        return label.startsWith("Wormholes portal layer ") ? PortalStencil.mainDepthFormat(depth) : depth;
    }
}
