package art.arcane.automator.mixin;

import art.arcane.automator.HiddenRenderer;
import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Window.class)
public abstract class WindowMixin {
    @Shadow private int framebufferWidth;
    @Shadow private int framebufferHeight;

    @Inject(method = "createGlfwWindow", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/systems/GpuBackend;setWindowHints()V", shift = At.Shift.AFTER))
    private static void hiddenWindowHints(CallbackInfoReturnable<Long> callback) {
        if (HiddenRenderer.enabled()) {
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_COCOA_RETINA_FRAMEBUFFER, GLFW.GLFW_FALSE);
        }
    }

    @ModifyVariable(method = "createGlfwWindow", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static int hiddenWidth(int width) {
        return HiddenRenderer.enabled() ? HiddenRenderer.width() : width;
    }

    @ModifyVariable(method = "createGlfwWindow", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private static int hiddenHeight(int height) {
        return HiddenRenderer.enabled() ? HiddenRenderer.height() : height;
    }

    @ModifyVariable(method = "createGlfwWindow", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static long hiddenMonitor(long monitor) {
        return HiddenRenderer.enabled() ? 0L : monitor;
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/platform/DisplayData;isFullscreen()Z"))
    private boolean initialFullscreen(DisplayData display) {
        return !HiddenRenderer.enabled() && display.isFullscreen();
    }

    @Inject(method = {"setMode", "toggleFullScreen", "selectCursor", "updateRawMouseInput"}, at = @At("HEAD"), cancellable = true)
    private void suppressDesktopChanges(CallbackInfo callback) {
        if (HiddenRenderer.enabled()) {
            callback.cancel();
        }
    }

    @Inject(method = "refreshFramebufferSize", at = @At("TAIL"))
    private void framebufferSize(CallbackInfo callback) {
        if (HiddenRenderer.enabled()) {
            framebufferWidth = HiddenRenderer.width();
            framebufferHeight = HiddenRenderer.height();
        }
    }

    @Inject(method = {"isFocused", "isIconified", "isMinimized"}, at = @At("HEAD"), cancellable = true)
    private void renderHiddenWindow(CallbackInfoReturnable<Boolean> callback) {
        if (HiddenRenderer.enabled()) {
            callback.setReturnValue(false);
        }
    }
}
