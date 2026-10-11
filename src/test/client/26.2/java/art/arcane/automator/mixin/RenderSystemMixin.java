package art.arcane.automator.mixin;

import art.arcane.automator.HiddenRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.util.TimeSource;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {
    @Inject(method = "initBackendSystem", at = @At("HEAD"))
    private static void beforeVideoInit(CallbackInfoReturnable<TimeSource.NanoTimeSource> callback) {
        if (HiddenRenderer.enabled()) {
            GLFW.glfwInitHint(GLFW.GLFW_COCOA_MENUBAR, GLFW.GLFW_FALSE);
        }
    }
}
