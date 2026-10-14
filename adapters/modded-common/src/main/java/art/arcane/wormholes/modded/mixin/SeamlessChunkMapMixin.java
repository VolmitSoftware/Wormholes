package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.seamless.HeldChunkSender;
import art.arcane.wormholes.modded.seamless.HeldChunks;
import art.arcane.wormholes.modded.seamless.UnsentChunkChanges;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkMap.class)
public abstract class SeamlessChunkMapMixin {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private Int2ObjectMap<?> entityMap;

    @WrapOperation(method = "removeEntity", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkMap$TrackedEntity;removePlayer(Lnet/minecraft/server/level/ServerPlayer;)V"))
    private void wormholesKeepRemoteTracking(@Coerce Object tracked, ServerPlayer player, Operation<Void> original) {
        if (!moving(player)) {
            original.call(tracked, player);
        }
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

    @WrapOperation(method = "onChunkReadyToSend", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkMap;markChunkPendingToSend(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/chunk/LevelChunk;)V"))
    private void wormholesSkipHeldChunks(ServerPlayer player, LevelChunk chunk, Operation<Void> original, @Local(argsOnly = true) ChunkHolder holder) {
        UnsentChunkChanges changes = (UnsentChunkChanges) holder;
        HeldChunks.Ready ready = ((HeldChunkSender) player.connection.chunkSender).wormholesHeldChunks().ready(chunk.getPos().pack(),
            changes.wormholesUnsentChange(), changes.wormholesUnsentChangeAt());
        if (ready == HeldChunks.Ready.SEND) {
            original.call(player, chunk);
        } else if (ready == HeldChunks.Ready.REFRESH) {
            player.connection.send(new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null));
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
