package art.arcane.automator.mixin;

import art.arcane.automator.HiddenRenderer;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InputConstants.class)
public abstract class InputConstantsMixin {
    @Inject(method = {"grabMouse", "releaseMouse"}, at = @At("HEAD"), cancellable = true)
    private static void suppressPlatformMouse(Window window, double x, double y, CallbackInfo callback) {
        if (HiddenRenderer.enabled()) {
            callback.cancel();
        }
    }
}
