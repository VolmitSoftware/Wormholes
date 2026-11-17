package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ClientMeshEntities;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class MeshEntityFluidMixin {
    @Inject(method = "touchingUnloadedChunk()Z", at = @At("HEAD"), cancellable = true)
    private void wormholesSnapshotFluid(CallbackInfoReturnable<Boolean> callback) {
        if (ClientMeshEntities.active(((Entity) (Object) this).level()) != null) {
            callback.setReturnValue(false);
        }
    }
}
