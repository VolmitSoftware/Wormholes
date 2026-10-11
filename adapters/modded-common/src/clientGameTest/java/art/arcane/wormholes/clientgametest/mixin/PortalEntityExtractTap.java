package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.PortalEntityRenderTap;
import art.arcane.wormholes.modded.mixin.client.PortalLevelExtractorAccess;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public abstract class PortalEntityExtractTap {
    @Inject(method = "extract", at = @At("TAIL"))
    private void wormholesTest$extracted(DeltaTracker tracker, Camera camera, float partialTicks, CallbackInfo callback) {
        PortalEntityRenderTap.extracted(((PortalLevelExtractorAccess) this).wormholes$portalState());
    }
}
