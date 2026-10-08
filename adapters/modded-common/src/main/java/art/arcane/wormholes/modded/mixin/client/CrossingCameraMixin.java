package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ClientSeamlessTravel;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.PortalShaderWarmup;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class CrossingCameraMixin {
    @Inject(method = "update", at = @At("TAIL"))
    private void wormholes$crossing(DeltaTracker tracker, CallbackInfo callback) {
        PortalShaderWarmup.shared().beginFrame();
        WormholesClient client = WormholesClient.instance();
        if (client == null) {
            return;
        }
        Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
        ClientSeamlessTravel travel = client.seamlessTravel();
        if (travel.beforeFrame(camera, tracker)) {
            camera.update(tracker);
        }
        travel.cameraRoll().apply(camera, System.currentTimeMillis());
    }
}
