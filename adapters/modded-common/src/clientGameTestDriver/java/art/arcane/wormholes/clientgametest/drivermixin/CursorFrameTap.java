package art.arcane.wormholes.clientgametest.drivermixin;

import art.arcane.wormholes.clientgametest.ClientGameTestDriver;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class CursorFrameTap {
    @Inject(method = "handleAccumulatedMovement", at = @At("HEAD"))
    private void wormholesTest$cursor(CallbackInfo callback) {
        ClientGameTestDriver.cursorFrame();
    }
}
