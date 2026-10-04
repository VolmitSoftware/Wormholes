package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.WormholesClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class PreparedTravelCameraMixin {
    @Inject(method = "update", at = @At("TAIL"))
    private void wormholes$crossing(DeltaTracker tracker, CallbackInfo callback) {
        WormholesClient client = WormholesClient.instance();
        Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
        if (client != null && client.preparedTravel().beforeFrame(camera, tracker)) {
            camera.update(tracker);
        }
    }
}
