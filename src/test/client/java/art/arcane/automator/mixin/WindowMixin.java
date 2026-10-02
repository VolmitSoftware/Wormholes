package art.arcane.automator.mixin;

import art.arcane.automator.HiddenRenderer;
import com.mojang.blaze3d.platform.Window;
import com.mojang.renderpearl.api.device.GpuBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Window.class)
public abstract class WindowMixin {
    @Shadow
    private int width;
    @Shadow
    private int height;
    @Shadow
    private int windowedWidth;
    @Shadow
    private int windowedHeight;
    @Shadow
    private boolean fullscreenRequested;

    @Redirect(method = "createWindow", at = @At(value = "INVOKE",
        target = "Lcom/mojang/renderpearl/api/device/GpuBackend;createWindow(Ljava/lang/String;IIJ)J"))
    private long hiddenWindow(GpuBackend backend, String title, int initialWidth, int initialHeight, long flags) {
        if (HiddenRenderer.enabled()) {
            width = HiddenRenderer.width();
            height = HiddenRenderer.height();
            windowedWidth = width;
            windowedHeight = height;
            fullscreenRequested = false;
            return backend.createWindow(title, width, height, HiddenRenderer.windowFlags(flags));
        }
        return backend.createWindow(title, initialWidth, initialHeight, flags);
    }

    @Inject(method = {"setMode", "restoreWindow", "updateWindowMouseGrab", "selectCursor"}, at = @At("HEAD"), cancellable = true)
    private void suppressDesktopChanges(CallbackInfo callback) {
        if (HiddenRenderer.enabled()) {
            callback.cancel();
        }
    }

    @Inject(method = "queryFramebufferSize", at = @At("HEAD"), cancellable = true)
    private void framebufferSize(CallbackInfoReturnable<Window.FramebufferSize> callback) {
        if (HiddenRenderer.enabled()) {
            callback.setReturnValue(new Window.FramebufferSize(HiddenRenderer.width(), HiddenRenderer.height()));
        }
    }

    @Inject(method = "isFocused", at = @At("HEAD"), cancellable = true)
    private void unfocused(CallbackInfoReturnable<Boolean> callback) {
        if (HiddenRenderer.enabled()) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "isIconified", at = @At("HEAD"), cancellable = true)
    private void renderHiddenWindow(CallbackInfoReturnable<Boolean> callback) {
        if (HiddenRenderer.enabled()) {
            callback.setReturnValue(false);
        }
    }
}
