package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalShaderScope;
import art.arcane.wormholes.modded.client.render.PortalShaderContext;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisPortalTextureMixin {
    @Inject(method = "onSetAlbedoTex", at = @At("HEAD"), cancellable = true)
    private void wormholes$preserveSourceMaterial(GpuTextureView texture, CallbackInfo callback) {
        if (PortalShaderScope.isRendering() && !PortalShaderContext.drawing()) {
            callback.cancel();
        }
    }
}
