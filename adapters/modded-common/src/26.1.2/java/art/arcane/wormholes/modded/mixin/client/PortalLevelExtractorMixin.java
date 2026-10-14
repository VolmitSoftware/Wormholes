package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class PortalLevelExtractorMixin {
    @Inject(method = "extractLevel", at = @At("TAIL"))
    private void wormholes$crossPortalEntities(DeltaTracker tracker, Camera camera, float partialTicks, CallbackInfo callback) {
        PortalLevelRendererAccess access = (PortalLevelRendererAccess) this;
        PortalStencilRenderer.instance().extracted(access.wormholes$portalLevel(), access.wormholes$portalState(), camera, partialTicks);
    }
}
