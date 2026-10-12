package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.IrisMeshHistoryTap;
import art.arcane.wormholes.modded.client.render.iris.IrisMeshFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = IrisMeshFrame.class, remap = false)
public abstract class IrisMeshHistoryFrameTap {
    @Inject(method = "open", at = @At("HEAD"))
    private static void wormholesTest$meshEnter(IrisMeshFrame.View view, CallbackInfoReturnable<IrisMeshFrame> callback) {
        IrisMeshHistoryTap.enter(view);
    }

    @Inject(method = "restore", at = @At("TAIL"))
    private void wormholesTest$meshLeave(CallbackInfo callback) {
        IrisMeshHistoryTap.leave();
    }
}
