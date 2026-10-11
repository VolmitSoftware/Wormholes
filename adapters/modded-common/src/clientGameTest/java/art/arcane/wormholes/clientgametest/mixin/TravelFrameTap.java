package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.TravelTap;
import art.arcane.wormholes.clientgametest.SeamlessTurningTap;
import art.arcane.wormholes.clientgametest.PortalEntityRenderTap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class TravelFrameTap {
    @Inject(method = "update", at = @At("HEAD"), require = 1, allow = 1)
    private void wormholesTest$cursorBeforeCamera(CallbackInfo callback) {
        SeamlessTurningTap.beforeRender(Minecraft.getInstance());
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void wormholesTest$beginEntities(CallbackInfo callback) {
        TravelTap.frameStarted(Minecraft.getInstance());
        PortalEntityRenderTap.beginFrame();
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void wormholesTest$frame(CallbackInfo callback) {
        TravelTap.frame(Minecraft.getInstance());
        PortalEntityRenderTap.endFrame();
    }
}
