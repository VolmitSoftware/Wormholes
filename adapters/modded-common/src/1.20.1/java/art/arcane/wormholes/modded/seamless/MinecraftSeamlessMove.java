package art.arcane.wormholes.modded.seamless;

import art.arcane.wormholes.modded.MinecraftPortalRegistry;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.mixin.SeamlessEntityAccess;
import art.arcane.wormholes.modded.mixin.SeamlessPlayerAccess;
import art.arcane.wormholes.network.client.TravelMessage;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

public final class MinecraftSeamlessMove implements SeamlessMove.Steps {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");

    private final Context context;
    private final ServerLevel origin;
    private TrackingView departedView = TrackingView.EMPTY;
    private LongOpenHashSet departedPending = new LongOpenHashSet();
    private Vec3 departedPosition;
    private boolean returnAdopted;
    private boolean handedOver;

    private MinecraftSeamlessMove(Context context) {
        this.context = Objects.requireNonNull(context, "context");
        this.origin = context.player().serverLevel();
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

    static List<ClientboundGameEventPacket> weather(boolean raining, float rainLevel, float thunderLevel) {
        return List.of(new ClientboundGameEventPacket(raining ? ClientboundGameEventPacket.START_RAINING : ClientboundGameEventPacket.STOP_RAINING, 0.0F),
            new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, rainLevel),
            new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, thunderLevel));
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
        departedView = TrackingView.of(player);
        LongOpenHashSet pending = new LongOpenHashSet();
        departedView.forEach(position -> {
            long key = position.toLong();
            if (!RemoteRoutes.ticking(origin, key)) {
                pending.add(key);
            }
        });
        departedPending = pending;
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
        returnAdopted = context.runtime().remoteRoutes().handOver(player, new RemoteRoutes.HandOver(context.forward(), origin,
            departedView, departedPending, context.back()), context.tick());
        handedOver = context.forward() != null && context.forward().resident();
    }

    @Override
    public void abandonHandOver(RuntimeException failure) {
        ServerPlayer player = context.player();
        LOGGER.error("Seamless handover of {} from {} to {} failed; continuing with vanilla chunk and entity tracking", player.getUUID(),
            origin.dimension().location(), context.destination().dimension().location(), failure);
        context.runtime().remoteRoutes().abandon(player, origin, context.destination() != origin);
        returnAdopted = false;
        handedOver = false;
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
        player.connection.send(new ClientboundSetTimePacket(destination.getGameTime(), destination.getDayTime(),
            destination.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)));
        player.connection.send(new ClientboundSetDefaultSpawnPositionPacket(destination.getSharedSpawnPos(), destination.getSharedSpawnAngle()));
        for (ClientboundGameEventPacket weather : weather(destination.isRaining(), destination.getRainLevel(1.0F), destination.getThunderLevel(1.0F))) {
            player.connection.send(weather);
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
                watcher.teleportTo(context.destination(), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
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
        PositionMoveRotation pose = context.pose();
        player.moveTo(pose.position().x, pose.position().y, pose.position().z, pose.yRot(), pose.xRot());
        player.setYHeadRot(pose.yRot());
        player.setDeltaMovement(pose.deltaMovement());
        player.connection.resetPosition();
        player.setOnGroundWithKnownMovement(player.onGround(), Vec3.ZERO);
    }

    @Override
    public void track() {
        ServerPlayer player = context.player();
        if (!handedOver) {
            player.serverLevel().getChunkSource().move(player);
            return;
        }
        context.runtime().seamlessMoving(player, true);
        try {
            player.serverLevel().getChunkSource().move(player);
        } finally {
            context.runtime().seamlessMoving(player, false);
        }
    }

    private void finish() {
        ServerPlayer player = context.player();
        RemoteRoute forward = context.forward();
        if (forward != null && forward.resident() && !returnAdopted) {
            context.sink().test(new TravelMessage.RemoteLevelClose(context.accept().levelHandle()));
        }
        RemoteRoutes.Return back = context.back();
        if (!straddles(back, returnAdopted)) {
            StraddleTracker.clear(player);
            return;
        }
        Vec3 eye = player.getEyePosition();
        StraddleTracker.register(player, StraddleTracker.create(RemoteRoutes.endpoint(back.source(), null), RemoteRoutes.endpoint(back.destination(), null),
            origin, new Vec3d(eye.x, eye.y, eye.z), MinecraftPortalRegistry.travelScale(back.source(), back.destination())));
    }

    static boolean straddles(RemoteRoutes.Return back, boolean adopted) {
        return back != null && adopted && back.traversable() && !back.source().getDimensionalKind().isManagedEndPortal();
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
