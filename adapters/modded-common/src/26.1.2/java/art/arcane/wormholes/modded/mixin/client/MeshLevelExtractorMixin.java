package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ClientMeshEntities;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class MeshLevelExtractorMixin {
    @WrapOperation(method = "extractVisibleEntities", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z"))
    private boolean wormholesPortalEntities(EntityRenderDispatcher dispatcher, Entity entity, Frustum frustum, double x, double y, double z,
                                            Operation<Boolean> original) {
        return !ClientMeshEntities.hiddenFromWorld(entity) && original.call(dispatcher, entity, frustum, x, y, z);
    }

    @Inject(method = "extractLevel", at = @At("TAIL"))
    private void wormholesExtractPortalEntities(DeltaTracker delta, Camera camera, float partialTick, CallbackInfo callback) {
        WormholesClient client = WormholesClient.instance();
        if (client != null && !PortalStencilRenderer.instance().nested()) {
            client.meshViews().extract(camera, partialTick);
        }
    }
}
