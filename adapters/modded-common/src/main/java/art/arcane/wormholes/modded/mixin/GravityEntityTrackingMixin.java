package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.WormholesModRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class GravityEntityTrackingMixin {
    @Unique
    private boolean wormholes$alreadyTracking;

    @Inject(method = "updatePlayer", at = @At("HEAD"))
    private void wormholes$beforeTracking(ServerPlayer player, CallbackInfo callback) {
        wormholes$alreadyTracking = ((RemoteTrackedEntityAccess) this).wormholesSeenBy().contains(player.connection);
    }

    @Inject(method = "updatePlayer", at = @At("TAIL"))
    private void wormholes$afterTracking(ServerPlayer player, CallbackInfo callback) {
        RemoteTrackedEntityAccess tracked = (RemoteTrackedEntityAccess) this;
        if (wormholes$alreadyTracking || !tracked.wormholesSeenBy().contains(player.connection)) {
            return;
        }
        Entity entity = tracked.wormholesTrackedEntity();
        if (entity.level() instanceof ServerLevel level) {
            WormholesModRuntime runtime = WormholesModRuntime.forServer(level.getServer());
            if (runtime != null) {
                runtime.clientViews().entityGravity(entity, player.connection);
            }
        }
    }
}
