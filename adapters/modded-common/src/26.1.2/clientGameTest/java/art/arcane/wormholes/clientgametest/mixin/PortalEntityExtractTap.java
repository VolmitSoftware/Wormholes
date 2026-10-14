package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.PortalEntityRenderTap;
import art.arcane.wormholes.modded.mixin.client.PortalLevelRendererAccess;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class PortalEntityExtractTap {
    @Inject(method = "extractLevel", at = @At("TAIL"))
    private void wormholesTest$extracted(DeltaTracker tracker, Camera camera, float partialTicks, CallbackInfo callback) {
        PortalEntityRenderTap.extracted(((PortalLevelRendererAccess) this).wormholes$portalState());
    }
}
