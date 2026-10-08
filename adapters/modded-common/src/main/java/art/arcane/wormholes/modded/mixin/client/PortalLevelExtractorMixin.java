package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public abstract class PortalLevelExtractorMixin {
    @WrapOperation(method = "extract", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/SectionUpdateTracker;repositionCamera(Lnet/minecraft/core/SectionPos;)V"))
    private void wormholes$keepSharedTracker(SectionUpdateTracker tracker, SectionPos camera, Operation<Void> original) {
        if (!PortalStencilRenderer.instance().sharedLayer()) {
            original.call(tracker, camera);
        }
    }

    @WrapOperation(method = "extract", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/multiplayer/ClientChunkCache;flipUpdateTrackingSets()V"))
    private void wormholes$keepSharedChunkUpdates(ClientChunkCache cache, Operation<Void> original) {
        if (!PortalStencilRenderer.instance().sharedLayer()) {
            original.call(cache);
        }
    }

    @Inject(method = "extract", at = @At("TAIL"))
    private void wormholes$crossPortalEntities(DeltaTracker tracker, Camera camera, float partialTicks, CallbackInfo callback) {
        PortalLevelExtractorAccess access = (PortalLevelExtractorAccess) this;
        PortalStencilRenderer.instance().extracted(access.wormholes$portalLevel(), access.wormholes$portalState(), camera, partialTicks);
    }
}
