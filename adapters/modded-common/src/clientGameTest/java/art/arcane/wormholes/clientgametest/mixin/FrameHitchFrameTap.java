package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.FrameHitchTap;
import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import net.minecraft.client.FramerateLimiter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class FrameHitchFrameTap {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void wormholesHitch$frameStarted(boolean advanceGameTime, CallbackInfo callback) {
        FrameHitchTap.frameStarted();
    }

    @Inject(method = "runTick", at = @At("RETURN"))
    private void wormholesHitch$frameEnded(boolean advanceGameTime, CallbackInfo callback) {
        FrameHitchTap.frameEnded();
    }

    @Mixin(FramerateLimiter.class)
    public abstract static class Limiter {
        @Inject(method = "limitDisplayFPS", at = @At("HEAD"))
        private static void wormholesHitch$limitStarted(int framerateLimit, CallbackInfo callback) {
            FrameHitchTap.limiterStarted();
        }

        @Inject(method = "limitDisplayFPS", at = @At("RETURN"))
        private static void wormholesHitch$limitEnded(int framerateLimit, CallbackInfo callback) {
            FrameHitchTap.limiterEnded();
        }
    }

    @Mixin(targets = "art.arcane.wormholes.modded.client.render.stencil.PortalWorldRenderer", remap = false)
    public abstract static class Views {
        @Inject(method = "render(Lart/arcane/wormholes/modded/client/render/stencil/PortalView;Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4f;IZZ)V", at = @At("HEAD"))
        private void wormholesHitch$viewDrawn(PortalView view, CameraRenderState outer, Matrix4f projection, int depth, boolean mirrored,
                                              boolean outerMirrored, CallbackInfo callback) {
            FrameHitchTap.viewDrawn(depth);
        }
    }

    @Mixin(targets = "art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer", remap = false)
    public abstract static class Shaded {
        @Inject(method = "shadedView", at = @At("HEAD"))
        private void wormholesHitch$shadedViewDrawn(CallbackInfo callback) {
            FrameHitchTap.viewDrawn(((PortalStencilRenderer) (Object) this).nested() ? 2 : 1);
        }
    }
}
