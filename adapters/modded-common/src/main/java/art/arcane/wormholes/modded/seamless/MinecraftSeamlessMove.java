package art.arcane.wormholes.modded.seamless;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.mixin.SeamlessChunkSenderAccess;
import art.arcane.wormholes.modded.mixin.SeamlessEntityAccess;
import art.arcane.wormholes.modded.mixin.SeamlessPlayerAccess;
import art.arcane.wormholes.network.client.TravelMessage;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

public final class MinecraftSeamlessMove implements SeamlessMove.Steps {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");

    private final Context context;
    private final ServerLevel origin;
    private ChunkTrackingView departedView = ChunkTrackingView.EMPTY;
    private LongOpenHashSet departedPending = new LongOpenHashSet();
    private Vec3 departedPosition;
    private boolean returnAdopted;

    private MinecraftSeamlessMove(Context context) {
        this.context = Objects.requireNonNull(context, "context");
        this.origin = context.player().level();
        this.departedPosition = context.player().position();
    }

    public static boolean run(Context context) {
        MinecraftSeamlessMove move = new MinecraftSeamlessMove(context);
        boolean moved = context.destination() == move.origin
            ? SeamlessMove.sameLevel(move, context.forward() != null && context.forward().resident())
            : SeamlessMove.crossLevel(move);
        if (moved) {
            move.finish();
        }
        return moved;
    }

    @Override
    public void moving(boolean moving) {
        context.runtime().seamlessMoving(context.player(), moving);
    }

    @Override
    public boolean allowLevelChange() {
        return context.runtime().seamlessEvents().allowLevelChange(context.player(), context.destination());
    }

    @Override
    public boolean accept() {
        return context.sink().test(context.accept());
    }

    @Override
    public void departLevel() {
        departView();
        origin.removePlayerImmediately(context.player(), Entity.RemovalReason.CHANGED_DIMENSION);
    }

    @Override
    public void departView() {
        ServerPlayer player = context.player();
        departedView = player.getChunkTrackingView();
        departedPending = new LongOpenHashSet(((SeamlessChunkSenderAccess) player.connection.chunkSender).wormholesPendingChunks());
        departedPosition = player.position();
    }

    @Override
    public void enterLevel() {
        ServerPlayer player = context.player();
        ((SeamlessEntityAccess) player).wormholesUnsetRemoved();
        player.setServerLevel(context.destination());
        reposition();
    }

    @Override
    public void handOver() {
        ServerPlayer player = context.player();
        ((SeamlessChunkSenderAccess) player.connection.chunkSender).wormholesPendingChunks().clear();
        returnAdopted = context.runtime().remoteRoutes().handOver(player, new RemoteRoutes.HandOver(context.forward(), origin,
            departedView, departedPending, context.back()), context.tick());
    }

    @Override
    public void abandonHandOver(RuntimeException failure) {
        ServerPlayer player = context.player();
        LOGGER.error("Seamless handover of {} from {} to {} failed; continuing with vanilla chunk and entity tracking", player.getUUID(),
            origin.dimension().identifier(), context.destination().dimension().identifier(), failure);
        context.runtime().remoteRoutes().abandon(player, origin, context.destination() != origin);
        returnAdopted = false;
    }

    @Override
    public void addToLevel() {
        context.destination().addDuringTeleport(context.player());
    }

    @Override
    public void dimensionTriggers() {
        ServerPlayer player = context.player();
        if (origin.dimension() == Level.OVERWORLD && context.destination().dimension() == Level.NETHER) {
            ((SeamlessPlayerAccess) player).wormholesEnteredNetherPosition(departedPosition);
        }
        ((SeamlessPlayerAccess) player).wormholesTriggerDimensionChange(origin);
        player.stopUsingItem();
    }

    @Override
    public void levelInfo() {
        ServerPlayer player = context.player();
        ServerLevel destination = context.destination();
        LevelData data = destination.getLevelData();
        player.connection.send(new ClientboundInitializeBorderPacket(destination.getWorldBorder()));
        player.connection.send(destination.getServer().clockManager().createFullSyncPacket());
        player.connection.send(new ClientboundSetDefaultSpawnPositionPacket(destination.getRespawnData()));
        if (destination.isRaining()) {
            player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 0.0F));
            player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, destination.getRainLevel(1.0F)));
            player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, destination.getThunderLevel(1.0F)));
        } else {
            player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.STOP_RAINING, 0.0F));
        }
        player.connection.send(new ClientboundChangeDifficultyPacket(data.getDifficulty(), data.isDifficultyLocked()));
    }

    @Override
    public void spectators() {
        ServerPlayer player = context.player();
        List<ServerPlayer> watchers = origin.players();
        for (int index = watchers.size() - 1; index >= 0; index--) {
            ServerPlayer watcher = watchers.get(index);
            if (watcher.getCamera() == player) {
                watcher.teleport(new TeleportTransition(context.destination(), player.position(), Vec3.ZERO, player.getYRot(), player.getXRot(),
                    TeleportTransition.DO_NOTHING));
                watcher.setCamera(null);
            }
        }
    }

    @Override
    public void levelChanged() {
        context.runtime().seamlessEvents().levelChanged(context.player(), origin, context.destination());
    }

    @Override
    public void reposition() {
        ServerPlayer player = context.player();
        player.teleportSetPosition(context.pose(), Set.of());
        player.connection.resetPosition();
    }

    @Override
    public void track() {
        context.player().level().getChunkSource().move(context.player());
    }

    private void finish() {
        ServerPlayer player = context.player();
        RemoteRoute forward = context.forward();
        if (forward != null && forward.resident() && !returnAdopted) {
            context.sink().test(new TravelMessage.RemoteLevelClose(context.accept().levelHandle()));
        }
        RemoteRoutes.Return back = context.back();
        if (back == null) {
            StraddleTracker.clear(player);
            return;
        }
        StraddleTracker.register(player, StraddleTracker.create(RemoteRoutes.endpoint(back.source()), RemoteRoutes.endpoint(back.destination()),
            origin, new Vec3d(player.getX(), player.getEyeY(), player.getZ())));
    }

    public record Context(WormholesModRuntime runtime, ServerPlayer player, ServerLevel destination, PositionMoveRotation pose,
                          RemoteRoute forward, RemoteRoutes.Return back, TravelMessage.TravelAccept accept,
                          Predicate<TravelMessage> sink, long tick) {
        public Context {
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(destination, "destination");
            Objects.requireNonNull(pose, "pose");
            Objects.requireNonNull(accept, "accept");
            Objects.requireNonNull(sink, "sink");
        }
    }
}
