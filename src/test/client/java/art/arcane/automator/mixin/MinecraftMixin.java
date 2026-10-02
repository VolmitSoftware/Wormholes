package art.arcane.automator.mixin;

import art.arcane.automator.ClientBridge;
import art.arcane.automator.HiddenRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Redirect(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;configure(Lcom/mojang/renderpearl/api/device/GpuSurface$Configuration;)V"))
    private void configurePresentation(GpuSurface surface, GpuSurface.Configuration configuration) throws SurfaceException {
        if (!HiddenRenderer.enabled()) {
            surface.configure(configuration);
        }
    }

    @Redirect(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;acquireNextTexture()V"))
    private void acquirePresentation(GpuSurface surface) throws SurfaceException {
        if (!HiddenRenderer.enabled()) {
            surface.acquireNextTexture();
        }
    }

    @Redirect(method = "handleKeybinds", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;isMouseGrabbed()Z"))
    private boolean attackMouseGrabbed(MouseHandler mouse) {
        return mouse.isMouseGrabbed() || ClientBridge.hasActiveAttackLease();
    }

    @Inject(method = "pauseIfInactive", at = @At("HEAD"), cancellable = true)
    private void keepSimulationRunning(CallbackInfo callback) {
        if (HiddenRenderer.enabled()) {
            callback.cancel();
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void beforeTick(CallbackInfo callback) {
        ClientBridge.tick((Minecraft) (Object) this);
    }

    @Inject(method = "renderFrame", at = @At("HEAD"))
    private void beforeRender(boolean render, CallbackInfo callback) {
        ClientBridge.updateLook((Minecraft) (Object) this);
    }

    @Inject(method = "renderFrame", at = @At("RETURN"))
    private void afterRender(boolean render, CallbackInfo callback) {
        ClientBridge.captureFrame((Minecraft) (Object) this);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void closeBridge(CallbackInfo callback) {
        ClientBridge.close();
    }
}
