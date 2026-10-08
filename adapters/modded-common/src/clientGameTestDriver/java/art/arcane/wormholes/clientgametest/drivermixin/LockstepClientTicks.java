package art.arcane.wormholes.clientgametest.drivermixin;

import art.arcane.wormholes.clientgametest.DriverLockstep;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Minecraft.class)
public abstract class LockstepClientTicks {
    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/DeltaTracker$Timer;advanceGameTime(J)I"))
    private int wormholesTest$lockstepTicks(DeltaTracker.Timer timer, long currentMs) {
        return DriverLockstep.clientTicks((Minecraft) (Object) this, timer.advanceGameTime(currentMs));
    }
}
