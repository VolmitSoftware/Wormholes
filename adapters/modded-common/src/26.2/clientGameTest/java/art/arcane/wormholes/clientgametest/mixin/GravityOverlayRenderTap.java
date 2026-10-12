package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.GravityOverlayTap;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ScreenEffectRenderer.class)
public abstract class GravityOverlayRenderTap {
    @Inject(method = "submit", at = @At("HEAD"))
    private void wormholes$beginOverlay(CallbackInfo callback) {
        GravityOverlayTap.begin();
    }

    @Inject(method = "submitBlockSprite", at = @At("HEAD"))
    private static void wormholes$blockOverlaySubmitted(CallbackInfo callback) {
        GravityOverlayTap.submitted();
    }
}
