package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ClientMeshEntities;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class MeshLivingEntityMixin {
    @Inject(method = "tickHeadTurn", at = @At("HEAD"), cancellable = true)
    private void wormholesSnapshotBodyYaw(float yaw, CallbackInfo callback) {
        LivingEntity entity = (LivingEntity) (Object) this;
        if (ClientMeshEntities.hiddenFromWorld(entity)) {
            entity.yBodyRot = entity.getYRot();
            callback.cancel();
        }
    }
}
