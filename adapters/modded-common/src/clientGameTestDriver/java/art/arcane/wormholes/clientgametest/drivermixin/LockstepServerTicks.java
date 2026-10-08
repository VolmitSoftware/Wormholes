package art.arcane.wormholes.clientgametest.drivermixin;

import art.arcane.wormholes.clientgametest.DriverLockstep;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class LockstepServerTicks {
    @Inject(method = "processPacketsAndTick", at = @At("HEAD"))
    private void wormholesTest$awaitTick(boolean sprinting, CallbackInfo callback) {
        if ((Object) this instanceof IntegratedServer) {
            DriverLockstep.beforeServerTick((MinecraftServer) (Object) this);
        }
    }

    @Inject(method = "processPacketsAndTick", at = @At("RETURN"))
    private void wormholesTest$ticked(boolean sprinting, CallbackInfo callback) {
        if ((Object) this instanceof IntegratedServer) {
            DriverLockstep.afterServerTick();
        }
    }
}
