package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.WormholesModRuntime;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkMap.class)
public abstract class SeamlessChunkMapMixin {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private Int2ObjectMap<?> entityMap;

    @Inject(method = "removeEntity", at = @At("HEAD"), cancellable = true)
    private void wormholesKeepRemoteTracking(Entity entity, CallbackInfo callback) {
        if (!(entity instanceof ServerPlayer player) || !moving(player)) {
            return;
        }
        ((SeamlessChunkMapAccess) this).wormholesUpdatePlayerStatus(player, false);
        if (entityMap.remove(entity.getId()) instanceof RemoteTrackedEntityAccess tracked) {
            tracked.wormholesBroadcastRemoved();
        }
        callback.cancel();
    }

    @WrapOperation(method = "updatePlayerStatus", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkMap;applyChunkTrackingView(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/level/ChunkTrackingView;)V"))
    private void wormholesKeepDeliveredChunks(ChunkMap map, ServerPlayer player, ChunkTrackingView view, Operation<Void> original) {
        if (!moving(player)) {
            original.call(map, player, view);
        }
    }

    @WrapOperation(method = "updatePlayerStatus", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerPlayer;setChunkTrackingView(Lnet/minecraft/server/level/ChunkTrackingView;)V"))
    private void wormholesKeepDeliveredView(ServerPlayer player, ChunkTrackingView view, Operation<Void> original) {
        if (!moving(player)) {
            original.call(player, view);
        }
    }

    @Inject(method = "tick()V", at = @At("TAIL"))
    private void wormholesTickRemoteViewers(CallbackInfo callback) {
        WormholesModRuntime runtime = WormholesModRuntime.forServer(level.getServer());
        if (runtime != null) {
            runtime.remoteRoutes().tickViewers(level, entityMap, level.getServer().getTickCount());
        }
    }

    private boolean moving(ServerPlayer player) {
        WormholesModRuntime runtime = WormholesModRuntime.forServer(level.getServer());
        return runtime != null && runtime.seamlessMoving(player);
    }
}
