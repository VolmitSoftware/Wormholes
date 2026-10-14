package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.CrossingBenchTap;
import net.minecraft.client.FramerateLimiter;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class CrossingBenchFrameTap {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void wormholesBench$frameStarted(boolean advanceGameTime, CallbackInfo callback) {
        CrossingBenchTap.frameStarted();
    }

    @Inject(method = "runTick", at = @At("RETURN"))
    private void wormholesBench$frameEnded(boolean advanceGameTime, CallbackInfo callback) {
        CrossingBenchTap.frameEnded((Minecraft) (Object) this);
    }

    @Mixin(FramerateLimiter.class)
    public abstract static class Limiter {
        @Inject(method = "limitDisplayFPS", at = @At("HEAD"))
        private static void wormholesBench$limitStarted(int framerateLimit, CallbackInfo callback) {
            CrossingBenchTap.limiterStarted();
        }

        @Inject(method = "limitDisplayFPS", at = @At("RETURN"))
        private static void wormholesBench$limitEnded(int framerateLimit, CallbackInfo callback) {
            CrossingBenchTap.limiterEnded();
        }
    }
}
