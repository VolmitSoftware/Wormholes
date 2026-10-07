package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.seamless.RemoteRoute;
import art.arcane.wormholes.modded.seamless.RoutedOnlyPlayers;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.List;

@Mixin(ChunkHolder.class)
public abstract class RemoteViewerChunkMixin {
    @Unique
    private List<RemoteRoute> wormholes$routes = List.of();

    @ModifyExpressionValue(method = "broadcastChanges", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkHolder$PlayerProvider;getPlayers(Lnet/minecraft/world/level/ChunkPos;Z)Ljava/util/List;", ordinal = 0))
    private List<ServerPlayer> wormholesRemoteLightViewers(List<ServerPlayer> players, @Local(argsOnly = true) LevelChunk chunk) {
        return wormholesRemoteViewers(players, chunk, true);
    }

    @ModifyExpressionValue(method = "broadcastChanges", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkHolder$PlayerProvider;getPlayers(Lnet/minecraft/world/level/ChunkPos;Z)Ljava/util/List;", ordinal = 1))
    private List<ServerPlayer> wormholesRemoteBlockViewers(List<ServerPlayer> players, @Local(argsOnly = true) LevelChunk chunk) {
        return wormholesRemoteViewers(players, chunk, false);
    }

    @ModifyArg(method = {"broadcastChanges", "broadcastBlockEntity"}, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkHolder;broadcast(Ljava/util/List;Lnet/minecraft/network/protocol/Packet;)V"), index = 1)
    private Packet<?> wormholesRouteBroadcast(Packet<?> packet) {
        List<RemoteRoute> routes = wormholes$routes;
        for (int index = 0; index < routes.size(); index++) {
            RemoteRoute route = routes.get(index);
            if (route.viewer() != null) {
                route.viewer().send(packet);
            }
        }
        return packet;
    }

    @ModifyExpressionValue(method = "broadcastChanges", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkHolder;hasChangesToBroadcast()Z"))
    private boolean wormholesClearRoutes(boolean changes) {
        wormholes$routes = List.of();
        return changes;
    }

    @Unique
    private List<ServerPlayer> wormholesRemoteViewers(List<ServerPlayer> players, LevelChunk chunk, boolean border) {
        wormholes$routes = List.of();
        if (!(chunk.getLevel() instanceof ServerLevel level)) {
            return players;
        }
        WormholesModRuntime runtime = WormholesModRuntime.forServer(level.getServer());
        if (runtime == null) {
            return players;
        }
        ChunkPos pos = chunk.getPos();
        List<RemoteRoute> routes = runtime.remoteRoutes().covering(level, pos.x(), pos.z(), border);
        wormholes$routes = routes;
        return players.isEmpty() && !routes.isEmpty() ? RoutedOnlyPlayers.INSTANCE : players;
    }
}
