package art.arcane.automator.mixin;

import art.arcane.automator.HiddenRenderer;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FramerateLimitTracker.class)
public abstract class FramerateLimitTrackerMixin {
    @Inject(method = "getThrottleReason", at = @At("HEAD"), cancellable = true)
    private void keepRendering(CallbackInfoReturnable<FramerateLimitTracker.FramerateThrottleReason> callback) {
        if (HiddenRenderer.enabled()) {
            callback.setReturnValue(FramerateLimitTracker.FramerateThrottleReason.NONE);
        }
    }
}
