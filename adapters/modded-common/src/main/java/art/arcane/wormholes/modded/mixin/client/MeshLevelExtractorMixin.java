package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ClientMeshEntities;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelExtractor.class)
public abstract class MeshLevelExtractorMixin {
    @Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true)
    private void wormholesPortalEntities(Entity entity, Frustum frustum, double x, double y, double z, float partialTick, long sectionLimit,
                                        CallbackInfoReturnable<Boolean> callback) {
        if (ClientMeshEntities.hiddenFromWorld(entity)) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "extract", at = @At("TAIL"))
    private void wormholesExtractPortalEntities(DeltaTracker delta, Camera camera, float partialTick, CallbackInfo callback) {
        WormholesClient client = WormholesClient.instance();
        if (client != null && !PortalStencilRenderer.instance().nested()) {
            client.meshViews().extract(camera, partialTick);
        }
    }
}
