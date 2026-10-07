package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.TravelTap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class TravelFrameTap {
    @Inject(method = "render", at = @At("HEAD"))
    private void wormholesTest$frame(CallbackInfo callback) {
        TravelTap.frame(Minecraft.getInstance());
    }
}
