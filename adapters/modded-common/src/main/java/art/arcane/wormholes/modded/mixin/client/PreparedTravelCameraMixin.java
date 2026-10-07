package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.PortalShaderWarmup;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class PreparedTravelCameraMixin {
    @Shadow @Final private RenderTarget mainRenderTarget;
    @Shadow public abstract void update(DeltaTracker tracker);
    @Shadow public abstract void extract(DeltaTracker tracker, boolean renderLevel);
    @Unique private GpuTexture wormholes$lastFrameTexture;
    @Unique private DeltaTracker wormholes$heldTracker;
    @Unique private boolean wormholes$heldRenderLevel;

    @WrapMethod(method = "update")
    private void wormholes$holdUpdate(DeltaTracker tracker, Operation<Void> original) {
        wormholes$heldTracker = null;
        if (wormholes$canHoldFrame()) {
            wormholes$heldTracker = tracker;
            return;
        }
        original.call(tracker);
    }

    @WrapMethod(method = "extract")
    private void wormholes$holdExtract(DeltaTracker tracker, boolean renderLevel, Operation<Void> original) {
        if (wormholes$heldTracker != null) {
            if (wormholes$canHoldFrame()) {
                wormholes$heldRenderLevel = renderLevel;
                return;
            }
            wormholes$heldTracker = null;
            update(tracker);
        }
        original.call(tracker, renderLevel);
    }

    @WrapMethod(method = "render")
    private void wormholes$holdRender(Operation<Void> original) {
        DeltaTracker tracker = wormholes$heldTracker;
        if (tracker != null) {
            wormholes$heldTracker = null;
            if (wormholes$canHoldFrame()) {
                return;
            }
            update(tracker);
            extract(tracker, wormholes$heldRenderLevel);
        }
        wormholes$lastFrameTexture = null;
        original.call();
        wormholes$lastFrameTexture = mainRenderTarget.getColorTexture();
    }

    @Unique
    private boolean wormholes$canHoldFrame() {
        WormholesClient client = WormholesClient.instance();
        return client != null && client.preparedTravel().holdAuthoritativeFrame() && wormholes$lastFrameTexture != null
            && !wormholes$lastFrameTexture.isClosed() && wormholes$lastFrameTexture == mainRenderTarget.getColorTexture();
    }

    @Inject(method = "update", at = @At("TAIL"))
    private void wormholes$crossing(DeltaTracker tracker, CallbackInfo callback) {
        PortalShaderWarmup.shared().beginFrame();
        WormholesClient client = WormholesClient.instance();
        Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
        if (client != null && client.preparedTravel().beforeFrame(camera, tracker)) {
            camera.update(tracker);
        }
    }
}
