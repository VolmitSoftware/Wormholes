package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.seamless.RemoteViewer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class RemoteViewerTrackingMixin {
    @Inject(method = "broadcastRemoved", at = @At("HEAD"))
    private void wormholesRemoveRemoteViewers(CallbackInfo callback) {
        RemoteViewer.removed((RemoteTrackedEntityAccess) this);
    }
}
