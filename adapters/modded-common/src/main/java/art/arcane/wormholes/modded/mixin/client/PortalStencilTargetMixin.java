package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.stencil.PortalStencil;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.renderpearl.api.GpuFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(MainTarget.class)
public abstract class PortalStencilTargetMixin {
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;<init>(Ljava/lang/String;Lcom/mojang/renderpearl/api/GpuFormat;Lcom/mojang/renderpearl/api/GpuFormat;)V"),
        index = 2)
    private static GpuFormat wormholes$stencilDepth(GpuFormat depth) {
        return PortalStencil.mainDepthFormat(depth);
    }
}
