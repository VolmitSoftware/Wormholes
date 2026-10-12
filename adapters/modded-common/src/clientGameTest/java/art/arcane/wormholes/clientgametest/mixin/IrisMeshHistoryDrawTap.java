package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.IrisMeshHistoryTap;
import art.arcane.wormholes.modded.client.render.PortalGpuMesh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PortalGpuMesh.class, remap = false)
public abstract class IrisMeshHistoryDrawTap {
    @Inject(method = "draw", at = @At("TAIL"))
    private void wormholesTest$meshDraw(CallbackInfo callback) {
        IrisMeshHistoryTap.drawn();
    }
}
