package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ClientMeshEntities;
import net.minecraft.world.entity.Entity;
import net.minecraft.sounds.SoundEvent;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class MeshEntityTickMixin {
    @Inject(method = {"canSimulateMovement", "isEffectiveAi", "isLocalInstanceAuthoritative", "isPushedByFluid", "canSpawnSprintParticle"}, at = @At("HEAD"), cancellable = true)
    private void wormholesVisualSimulation(CallbackInfoReturnable<Boolean> callback) {
        if (ClientMeshEntities.hiddenFromWorld((Entity) (Object) this)) {
            callback.setReturnValue(false);
        }
    }
    @Inject(method = {"updateSwimming", "doWaterSplashEffect"}, at = @At("HEAD"), cancellable = true)
    private void wormholesSnapshotSwimming(CallbackInfo callback) {
        if (ClientMeshEntities.hiddenFromWorld((Entity) (Object) this)) {
            callback.cancel();
        }
    }

    @Inject(method = "playSound(Lnet/minecraft/sounds/SoundEvent;FF)V", at = @At("HEAD"), cancellable = true)
    private void wormholesSourceSound(SoundEvent sound, float volume, float pitch, CallbackInfo callback) {
        if (ClientMeshEntities.hiddenFromWorld((Entity) (Object) this)) {
            callback.cancel();
        }
    }

}
