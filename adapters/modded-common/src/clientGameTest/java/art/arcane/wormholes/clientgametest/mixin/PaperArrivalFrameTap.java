package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.PaperArrivalFrames;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class PaperArrivalFrameTap {
    @Inject(method = "render", at = @At("TAIL"))
    private void wormholesTest$paperArrivalFrame(CallbackInfo callback) {
        PaperArrivalFrames.endFrame();
    }
}
