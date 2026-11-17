package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.seamless.HeldChunkSender;
import art.arcane.wormholes.modded.seamless.HeldChunks;
import art.arcane.wormholes.modded.seamless.UnsentChunkChanges;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkMap.class)
public abstract class SeamlessChunkMapMixin {
    @Shadow @Final ServerLevel level;
    @Shadow @Final private Int2ObjectMap<?> entityMap;

    @Shadow
    protected abstract ChunkHolder getVisibleChunkIfPresent(long key);

    @WrapOperation(method = "removeEntity", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkMap$TrackedEntity;removePlayer(Lnet/minecraft/server/level/ServerPlayer;)V"))
    private void wormholesKeepRemoteTracking(@Coerce Object tracked, ServerPlayer player, Operation<Void> original) {
        if (!moving(player)) {
            original.call(tracked, player);
        }
    }

    @WrapOperation(method = {"updatePlayerStatus", "move"}, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkMap;updateChunkTracking(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/ChunkPos;Lorg/apache/commons/lang3/mutable/MutableObject;ZZ)V"))
    private void wormholesKeepDeliveredChunks(ChunkMap map, ServerPlayer player, ChunkPos pos,
                                              MutableObject<ClientboundLevelChunkWithLightPacket> packet, boolean was, boolean load,
                                              Operation<Void> original) {
        if (!moving(player)) {
            original.call(map, player, pos, packet, was, load);
        }
    }

    @Inject(method = "playerLoadedChunk", at = @At("HEAD"), cancellable = true)
    private void wormholesSkipHeldChunks(ServerPlayer player, MutableObject<ClientboundLevelChunkWithLightPacket> packet, LevelChunk chunk,
                                         CallbackInfo callback) {
        long key = chunk.getPos().toLong();
        ChunkHolder holder = getVisibleChunkIfPresent(key);
        boolean changed = holder instanceof UnsentChunkChanges changes && changes.wormholesUnsentChange();
        long changedAt = holder instanceof UnsentChunkChanges changes ? changes.wormholesUnsentChangeAt() : 0L;
        HeldChunks.Ready ready = ((HeldChunkSender) player).wormholesHeldChunks().ready(key, changed, changedAt);
        if (ready == HeldChunks.Ready.SKIP) {
            callback.cancel();
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
