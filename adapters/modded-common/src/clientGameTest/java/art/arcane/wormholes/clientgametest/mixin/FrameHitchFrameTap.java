package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.FrameHitchTap;
import net.minecraft.client.FramerateLimiter;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class FrameHitchFrameTap {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void wormholesHitch$frameStarted(boolean advanceGameTime, CallbackInfo callback) {
        FrameHitchTap.frameStarted();
    }

    @Inject(method = "runTick", at = @At("RETURN"))
    private void wormholesHitch$frameEnded(boolean advanceGameTime, CallbackInfo callback) {
        FrameHitchTap.frameEnded();
    }

    @Mixin(FramerateLimiter.class)
    public abstract static class Limiter {
        @Inject(method = "limitDisplayFPS", at = @At("HEAD"))
        private static void wormholesHitch$limitStarted(int framerateLimit, CallbackInfo callback) {
            FrameHitchTap.limiterStarted();
        }

        @Inject(method = "limitDisplayFPS", at = @At("RETURN"))
        private static void wormholesHitch$limitEnded(int framerateLimit, CallbackInfo callback) {
            FrameHitchTap.limiterEnded();
        }
    }
}
