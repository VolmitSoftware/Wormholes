package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.ClientViewRimParticles;
import art.arcane.wormholes.modded.client.ClientLevelScene;
import art.arcane.wormholes.modded.client.WormholesClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = WormholesClient.class, remap = false)
public abstract class ClientViewRimParticleTap {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/Minecraft;isWindowActive()Z"))
    private boolean wormholesTest$logicalActivity(Minecraft client, Operation<Boolean> original) {
        return ClientViewRimParticles.logicalWindowActive(client, original.call(client));
    }

    @Mixin(value = ClientLevelScene.class, remap = false)
    public abstract static class Dust {
        @WrapOperation(method = "dust", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
        private void wormholesTest$rimDust(ClientLevel level, ParticleOptions options, double x, double y, double z,
                                           double velocityX, double velocityY, double velocityZ, Operation<Void> original) {
            boolean previous = ClientViewRimParticles.beginDust(options, x, y, z);
            try {
                original.call(level, options, x, y, z, velocityX, velocityY, velocityZ);
            } finally {
                ClientViewRimParticles.endDust(previous);
            }
        }
    }

    @Mixin(ParticleEngine.class)
    public abstract static class Creation {
        @Inject(method = "createParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)Lnet/minecraft/client/particle/Particle;",
            at = @At("RETURN"))
        private void wormholesTest$createdDust(ParticleOptions options, double x, double y, double z,
                                               double velocityX, double velocityY, double velocityZ,
                                               CallbackInfoReturnable<Particle> callback) {
            ClientViewRimParticles.created((ParticleEngine) (Object) this, options, callback.getReturnValue());
        }
    }
}
