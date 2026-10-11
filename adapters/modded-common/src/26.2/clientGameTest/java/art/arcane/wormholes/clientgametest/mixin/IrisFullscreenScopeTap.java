package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.NativeIrisFullscreenAssertions;
import art.arcane.wormholes.modded.client.render.iris.IrisFullscreenScope;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = IrisFullscreenScope.class, remap = false)
public abstract class IrisFullscreenScopeTap {
    @Inject(method = "open", at = @At("RETURN"))
    private static void wormholesTest$scopes(CallbackInfoReturnable<IrisFullscreenScope> callback) {
        NativeIrisFullscreenAssertions.verify();
    }
}
