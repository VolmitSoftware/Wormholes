package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.WormholesClient;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public abstract class StraddleOverlayMixin {
    @Inject(method = "extractPlayerState", at = @At("TAIL"))
    private void wormholes$straddleOverlay(Camera camera, DeltaTracker tracker, float partialTicks, PlayerRenderState state, CallbackInfo callback) {
        WormholesClient client = WormholesClient.instance();
        if (client != null && client.preparedTravel().straddle() != null) {
            state.blockOverlay = null;
        }
    }
}
