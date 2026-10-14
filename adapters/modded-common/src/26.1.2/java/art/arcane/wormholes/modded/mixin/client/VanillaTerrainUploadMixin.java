package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelRenderer.class)
public abstract class VanillaTerrainUploadMixin {
    @WrapOperation(method = "prepareChunkRenders", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;uploadGlobalGeomBuffersToGPU()V"))
    private void wormholes$keepSharedTerrainBuffers(SectionRenderDispatcher dispatcher, Operation<Void> original) {
        if (!PortalStencilRenderer.instance().sharedLayer()) {
            original.call(dispatcher);
        }
    }
}
