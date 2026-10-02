package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalTerrainLighting;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.block.BlockModelLighter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockModelLighter.class)
public abstract class MeshBlockModelLighterMixin {
    @WrapOperation(method = "prepareQuadAmbientOcclusion", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ARGB;gray(F)I"))
    private int wormholes$separateAmbientOcclusion(float brightness, Operation<Integer> original) {
        return PortalTerrainLighting.current() == null ? original.call(brightness) : PortalTerrainLighting.ambientColor(brightness);
    }

    @Inject(method = "getDirectionalBrightness", at = @At("RETURN"), cancellable = true)
    private static void wormholes$workerDirectionalShading(CallbackInfoReturnable<Float> callback) {
        if (PortalTerrainLighting.current() != null) {
            callback.setReturnValue(PortalTerrainLighting.directionalBrightness(callback.getReturnValue()));
        }
    }
}
