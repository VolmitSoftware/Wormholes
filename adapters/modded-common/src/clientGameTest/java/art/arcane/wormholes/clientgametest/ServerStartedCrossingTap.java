package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.mixin.SeamlessListenerAccess;
import art.arcane.wormholes.modded.mixin.ServerConnectionAccess;
import art.arcane.wormholes.network.client.TravelMessage;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class ServerStartedCrossingTap {
    private static final List<Held> HELD = new ArrayList<>();
    private static final List<Move> MOVES = new ArrayList<>();
    private static UUID suppressedSource;
    private static int suppressedClaims;
    private static UUID recorded;
    private static UUID heldPlayer;
    private static Connection holding;
    private static int holdStarted = -1;
    private static int movesBeforeHold = -1;
    private static int released = -1;

    private ServerStartedCrossingTap() {
    }

    static synchronized void reset() {
        HELD.clear();
        MOVES.clear();
        suppressedSource = null;
        suppressedClaims = 0;
        recorded = null;
        heldPlayer = null;
        holding = null;
        holdStarted = -1;
        movesBeforeHold = -1;
        released = -1;
    }

    static synchronized void suppressClaims(UUID source) {
        suppressedSource = source;
    }

    static synchronized void allowClaims() {
        suppressedSource = null;
    }

    public static synchronized boolean claimSuppressed(TravelMessage.TravelBegin arm) {
        if (suppressedSource == null || !suppressedSource.equals(arm.sourcePortal())) {
            return false;
        }
        suppressedClaims++;
        return true;
    }

    static synchronized int suppressedClaims() {
        return suppressedClaims;
    }

    static synchronized void record(UUID player) {
        recorded = player;
    }

    public static synchronized boolean recording(ServerPlayer player) {
        return recorded != null && recorded.equals(player.getUUID());
    }

    public static synchronized void moved(ServerPlayer player, Vec3 requested, String levelBefore, Vec3 before) {
        MOVES.add(new Move(player.level().getServer().getTickCount(), requested, levelBefore, before, player.level().dimension().identifier().toString(),
            player.position(), ((SeamlessListenerAccess) player.connection).wormholesAwaitingPosition() != null));
    }

    static synchronized List<Move> moves() {
        return List.copyOf(MOVES);
    }

    static synchronized void holdNextMove(UUID player) {
        heldPlayer = player;
    }

    public static synchronized void moving(ServerPlayer player) {
        if (heldPlayer == null || holding != null || !heldPlayer.equals(player.getUUID())) {
            return;
        }
        holding = ((ServerConnectionAccess) player.connection).wormholesConnection();
        holdStarted = player.level().getServer().getTickCount();
        movesBeforeHold = MOVES.size();
    }

    public static synchronized boolean hold(Connection connection, Packet<?> packet, ChannelFutureListener listener, boolean flush) {
        if (holding == null || connection != holding) {
            return false;
        }
        HELD.add(new Held(packet, listener, flush));
        return true;
    }

    static synchronized int holdStarted() {
        return holdStarted;
    }

    static synchronized int movesBeforeHold() {
        return movesBeforeHold;
    }

    static synchronized int released() {
        return released;
    }

    static boolean release() {
        Connection connection;
        List<Held> queued;
        synchronized (ServerStartedCrossingTap.class) {
            heldPlayer = null;
            connection = holding;
            if (connection == null) {
                return false;
            }
            queued = new ArrayList<>(HELD);
            HELD.clear();
            holding = null;
            released = queued.size();
        }
        for (Held held : queued) {
            connection.send(held.packet(), held.listener(), held.flush());
        }
        connection.flushChannel();
        return true;
    }

    private record Held(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
    }

    record Move(int serverTick, Vec3 requested, String levelBefore, Vec3 before, String levelAfter, Vec3 after, boolean awaiting) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "server tick %d move %s in %s from %s to %s %s awaiting %s", serverTick,
                requested == null ? "rotation" : vector(requested), levelBefore, vector(before), levelAfter, vector(after), awaiting);
        }

        private static String vector(Vec3 vector) {
            return String.format(Locale.ROOT, "%.3f,%.3f,%.3f", vector.x, vector.y, vector.z);
        }
    }
}
