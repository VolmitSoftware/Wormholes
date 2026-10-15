package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.NetherReturnTerrainTap;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class NetherReturnTerrainFrameTap {
    @Inject(method = "render", at = @At("HEAD"), require = 1, allow = 1)
    private void wormholesTest$beginNetherReturnFrame(CallbackInfo callback) {
        NetherReturnTerrainTap.beginFrame();
    }

    @Inject(method = "render", at = @At("TAIL"), require = 1, allow = 1)
    private void wormholesTest$endNetherReturnFrame(CallbackInfo callback) {
        NetherReturnTerrainTap.endFrame();
    }
}
