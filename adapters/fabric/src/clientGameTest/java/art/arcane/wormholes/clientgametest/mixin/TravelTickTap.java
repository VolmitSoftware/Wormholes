package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.TravelTap;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class TravelTickTap {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void wormholesTest$tickStarted(boolean advanceGameTime, CallbackInfo callback) {
        TravelTap.tickStarted();
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void wormholesTest$gameTick(CallbackInfo callback) {
        TravelTap.gameTick();
    }

    @Inject(method = "runTick", at = @At("RETURN"))
    private void wormholesTest$tickEnded(boolean advanceGameTime, CallbackInfo callback) {
        TravelTap.tickEnded();
    }
}
