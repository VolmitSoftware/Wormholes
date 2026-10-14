package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.clientgametest.mixin.HeldChunksAccess;
import art.arcane.wormholes.modded.mixin.SeamlessChunkMapAccess;
import art.arcane.wormholes.modded.seamless.HeldChunkSender;
import art.arcane.wormholes.transit.OrientationPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

public final class SeamlessHeldChunk {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final int NETHER_STAY_TICKS = 100;
    private static final int RETURN_TIMEOUT_TICKS = 120;
    private static final int RESEND_TIMEOUT_TICKS = 300;
    private static final int WALK_TICKS = 10;
    private static final int SETTLE_TICKS = 5;
    private static final double MIN_WALK = 0.5D;
    private static final BlockPos SOURCE_MIN = new BlockPos(0, 70, 20);
    private static final BlockPos DESTINATION_MIN = new BlockPos(0, 70, 220);
    private static final int FLOOR_Y = 99;
    private static final int FLOOR_HALF = 4;
    private static final int MIN_CHUNK_DISTANCE = 2;
    private static volatile boolean armed;
    private static volatile Edit edit;
    private static volatile String failure;

    private SeamlessHeldChunk() {
    }

    static void run(SeamlessClient client, SeamlessServer server, String label) {
        SeamlessScenario.join(client);
        SeamlessScenario.assertSeamlessNegotiated(client);
        SeamlessScenario.Route route = server.build(spec());
        server.approach(route);
        SeamlessScenario.awaitPrepared(client);
        SeamlessScenario.walkThrough(client, label + "-out");
        client.waitTicks(NETHER_STAY_TICKS);
        SeamlessScenario.turnBack(client, route);
        Edit edit = null;
        arm();
        try {
            SeamlessScenario.walkThrough(client, label + "-return");
            for (int tick = 0; tick < RETURN_TIMEOUT_TICKS && edit == null; tick++) {
                edit = edit();
                if (edit == null) {
                    client.waitTicks(1);
                }
            }
        } finally {
            disarm();
        }
        SeamlessScenario.assertTrue(edit != null, "the player never returned to the overworld through the portal");
        Edit edited = edit;
        LOGGER.info("[{}] edited held chunk {} of {} held after the return hand-over", label, edited.chunk(), edited.held());
        server.approachFrom(Level.OVERWORLD, edited.standing(), 0.0F);
        int received = -1;
        for (int tick = 0; tick <= RESEND_TIMEOUT_TICKS && received < 0; tick++) {
            if (client.computeOnClient(minecraft -> minecraft.level.getBlockState(edited.floor()).is(Blocks.STONE))) {
                received = tick;
            } else {
                client.waitTicks(1);
            }
        }
        SeamlessScenario.assertTrue(received >= 0, "the client never received chunk " + edited.chunk()
            + ", which the return hand-over held and which changed before it could tick");
        LOGGER.info("[{}] edited chunk reached the client {} ticks after the teleport", label, received);
        Vec3 before = server.playerPosition();
        client.holdForwardFor(WALK_TICKS);
        client.waitTicks(SETTLE_TICKS);
        Vec3 after = server.playerPosition();
        double walked = after.subtract(before).horizontalDistance();
        SeamlessScenario.assertTrue(walked > MIN_WALK && Math.abs(after.y - edited.standing().y) < 1.0E-3D,
            "the player could not walk on the edited floor: moved " + walked + " from " + before + " to " + after);
        SeamlessScenario.finish(client, server, route);
    }

    private static SeamlessScenario.RouteSpec spec() {
        return new SeamlessScenario.RouteSpec(Level.OVERWORLD, SOURCE_MIN, Level.NETHER, DESTINATION_MIN, OrientationPolicy.FRAME, false);
    }

    private static void arm() {
        edit = null;
        failure = null;
        armed = true;
    }

    private static void disarm() {
        armed = false;
    }

    private static Edit edit() {
        String reason = failure;
        if (reason != null) {
            throw new AssertionError(reason);
        }
        return edit;
    }

    public static void moved(ServerPlayer player) {
        if (!armed || player.level().dimension() != Level.OVERWORLD) {
            return;
        }
        armed = false;
        try {
            edit = editHeld(player);
        } catch (AssertionError reason) {
            failure = reason.getMessage();
        }
    }

    private static Edit editHeld(ServerPlayer player) {
        ServerLevel level = player.level();
        HeldChunksAccess holds = (HeldChunksAccess) (Object) ((HeldChunkSender) player.connection.chunkSender).wormholesHeldChunks();
        long[] held = holds.wormholesTest$heldAt().keySet().toLongArray();
        Arrays.sort(held);
        ChunkPos at = player.chunkPosition();
        for (long key : held) {
            ChunkPos pos = ChunkPos.unpack(key);
            ChunkHolder holder = ((SeamlessChunkMapAccess) level.getChunkSource().chunkMap).wormholesVisibleChunk(key);
            if (Math.max(Math.abs(pos.x() - at.x()), Math.abs(pos.z() - at.z())) < MIN_CHUNK_DISTANCE || holder == null
                || holder.getTickingChunk() != null || level.getChunkSource().getChunkNow(pos.x(), pos.z()) == null) {
                continue;
            }
            BlockPos center = new BlockPos(pos.getMiddleBlockX(), FLOOR_Y, pos.getMiddleBlockZ());
            for (int x = -FLOOR_HALF; x <= FLOOR_HALF; x++) {
                for (int z = -FLOOR_HALF; z <= FLOOR_HALF; z++) {
                    level.setBlockAndUpdate(center.offset(x, 0, z), Blocks.STONE.defaultBlockState());
                }
            }
            return new Edit(pos, center, new Vec3(center.getX() + 0.5D, FLOOR_Y + 1.0D, center.getZ() + 0.5D), held.length);
        }
        throw new AssertionError("the return hand-over held no loaded chunk that was still waiting to tick (" + held.length + " held)");
    }

    record Edit(ChunkPos chunk, BlockPos floor, Vec3 standing, int held) {
    }
}
