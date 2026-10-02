package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalTerrainLighting;
import art.arcane.wormholes.modded.client.render.PortalTerrainMaterials;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings", remap = false)
public abstract class IrisPortalTerrainLightingMixin {
    @Inject(method = "getAmbientOcclusionLevel", at = @At("HEAD"), cancellable = true)
    private void wormholes$workerAmbientOcclusion(CallbackInfoReturnable<Float> callback) {
        PortalTerrainMaterials.Lighting lighting = PortalTerrainLighting.current();
        if (lighting != null) {
            callback.setReturnValue(lighting.ambientOcclusion());
        }
    }
}
