package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.ClientPreparedTravel;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.transit.OrientationPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;

final class SeamlessWalkThrough {
    static final BlockPos NETHER_FRAME = new BlockPos(0, 70, -40);
    static final BlockPos FRAME_SOURCE = new BlockPos(0, 70, 60);
    static final BlockPos FRAME_DESTINATION = new BlockPos(0, 70, 260);
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final int LIGHT_TIMEOUT_TICKS = 600;
    private static final int LOOK_TICKS = 40;
    private static final int ARRIVAL_PAUSE_TICKS = 10;
    private static final int CROSSING_TIMEOUT_TICKS = 160;
    private static final int PREPARE_TIMEOUT_TICKS = 600;
    private static final int SETTLED_FRAME = 10;
    private static final int BASELINE_FRAMES = 40;
    private static final int SETTLE_TIMEOUT_TICKS = 100;
    private static final double CROSSING_JUMP = 4.0D;
    private static final double APPROACH_BLOCKS = 6.5D;
    private static final double FAR_APPROACH_BLOCKS = 9.0D;

    private SeamlessWalkThrough() {
    }

    static void netherPortal(SeamlessClient client, SeamlessServer server, String label, int trips) {
        SeamlessScenario.join(client);
        SeamlessScenario.assertSeamlessNegotiated(client);
        server.lightNetherPortal(NETHER_FRAME);
        client.runOnClient(minecraft -> TravelTap.reset());
        int waited = 0;
        while (!server.netherPortalLit(NETHER_FRAME)) {
            SeamlessScenario.assertTrue(waited++ < LIGHT_TIMEOUT_TICKS, label + ": the lit nether portal was never replaced by a Wormholes portal");
            client.waitTicks(1);
        }
        LOGGER.info("[{}] nether portal replaced {} ticks after lighting", label, waited);
        List<Vec3> centers = server.netherPortalCenters(NETHER_FRAME);
        LOGGER.info("[{}] nether portal pair {}", label, centers);
        client.waitTicks(LOOK_TICKS);
        List<String> failures = new ArrayList<>();
        for (int trip = 1; trip <= trips; trip++) {
            face(client, centers.getFirst());
            walk(client, label + "-" + trip, failures);
            client.waitTicks(ARRIVAL_PAUSE_TICKS);
            face(client, centers.get(1));
            walk(client, label + "-" + trip + "-return", failures);
            client.waitTicks(ARRIVAL_PAUSE_TICKS);
        }
        client.restoreDefaultGameOptions();
        SeamlessScenario.assertTrue(failures.isEmpty(), label + ": " + failures);
    }

    static void framePortal(SeamlessClient client, SeamlessServer server, String label, int trips) {
        SeamlessScenario.join(client);
        SeamlessScenario.assertSeamlessNegotiated(client);
        SeamlessScenario.Route route = server.build(new SeamlessScenario.RouteSpec(Level.OVERWORLD, FRAME_SOURCE, Level.NETHER, FRAME_DESTINATION,
            OrientationPolicy.FRAME, false));
        server.approachFrom(Level.OVERWORLD, new Vec3(FRAME_SOURCE.getX() + 1.5D, FRAME_SOURCE.getY(), FRAME_SOURCE.getZ() + FAR_APPROACH_BLOCKS), 180.0F);
        client.waitForChunksDownload();
        client.runOnClient(minecraft -> TravelTap.reset());
        List<String> failures = new ArrayList<>();
        Vec3 source = Vec3.atLowerCornerOf(FRAME_SOURCE).add(1.5D, 1.5D, 0.5D);
        Vec3 destination = Vec3.atLowerCornerOf(FRAME_DESTINATION).add(1.5D, 1.5D, 0.5D);
        for (int trip = 1; trip <= trips; trip++) {
            face(client, source);
            walk(client, label + "-" + trip, failures);
            client.waitTicks(ARRIVAL_PAUSE_TICKS);
            face(client, destination);
            walk(client, label + "-" + trip + "-return", failures);
            client.waitTicks(ARRIVAL_PAUSE_TICKS);
        }
        SeamlessScenario.finish(client, server, route);
        SeamlessScenario.assertTrue(failures.isEmpty(), label + ": " + failures);
    }

    static void light(MinecraftServer server, ServerPlayer player, BlockPos min) {
        ServerLevel level = server.overworld();
        fill(level, min.offset(-6, -1, -8), 16, 1, 22, Blocks.STONE.defaultBlockState());
        fill(level, min.offset(-6, 0, -8), 16, 7, 22, Blocks.AIR.defaultBlockState());
        for (int x = -1; x <= 2; x++) {
            level.setBlockAndUpdate(min.offset(x, -1, 0), Blocks.OBSIDIAN.defaultBlockState());
            level.setBlockAndUpdate(min.offset(x, 3, 0), Blocks.OBSIDIAN.defaultBlockState());
        }
        for (int y = 0; y < 3; y++) {
            level.setBlockAndUpdate(min.offset(-1, y, 0), Blocks.OBSIDIAN.defaultBlockState());
            level.setBlockAndUpdate(min.offset(2, y, 0), Blocks.OBSIDIAN.defaultBlockState());
        }
        server.getPlayerList().op(player.nameAndId());
        player.teleportTo(level, min.getX() + 1.0D, min.getY(), min.getZ() + APPROACH_BLOCKS, Set.of(), 180.0F, 0.0F, false);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FLINT_AND_STEEL));
        BlockPos base = min.offset(0, -1, 0);
        InteractionResult result = player.gameMode.useItemOn(player, level, player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(base).add(0.0D, 0.5D, 0.0D), Direction.UP, base, false));
        SeamlessScenario.assertTrue(result.consumesAction(), "flint and steel was not used on the obsidian frame: " + result);
    }

    static boolean lit(MinecraftServer server, BlockPos min) {
        WormholesModRuntime runtime = runtime(server);
        MinecraftPortal portal = runtime.portals().at(server.overworld(), min);
        return portal != null && portal.isManaged() && portal.getDestinationId() != null;
    }

    private static void walk(SeamlessClient client, String label, List<String> failures) {
        String unprepared = prepare(client);
        if (unprepared != null) {
            LOGGER.info("[{}] walking before preparation finished: {}", label, unprepared);
        }
        int approach = client.computeOnClient(minecraft -> TravelTap.frames().size());
        String before = client.computeOnClient(minecraft -> minecraft.level.dimension().identifier().toString());
        int player = client.computeOnClient(minecraft -> System.identityHashCode(minecraft.player));
        client.holdForward();
        int crossing = -1;
        try {
            for (int tick = 0; tick < CROSSING_TIMEOUT_TICKS && crossing < 0; tick++) {
                client.waitTicks(1);
                crossing = client.computeOnClient(minecraft -> TravelTap.crossingFrame(CROSSING_JUMP, approach));
            }
        } finally {
            client.releaseForward();
        }
        if (crossing < 0) {
            failures.add(label + " never crossed");
            client.runOnClient(minecraft -> TravelTap.reset());
            LOGGER.info("[{}] never crossed from {}: player at {} yaw {}, travel {}", label, before,
                client.computeOnClient(minecraft -> minecraft.player.position()), client.computeOnClient(minecraft -> minecraft.player.getYRot()),
                client.computeOnClient(SeamlessWalkThrough::travelState));
            return;
        }
        client.waitTicks(ARRIVAL_PAUSE_TICKS);
        int settled = crossing + SETTLED_FRAME + BASELINE_FRAMES;
        for (int tick = 0; tick < SETTLE_TIMEOUT_TICKS && client.computeOnClient(minecraft -> TravelTap.frames().size()) < settled; tick++) {
            client.waitTicks(1);
        }
        String after = client.computeOnClient(minecraft -> minecraft.level.dimension().identifier().toString());
        int respawns = TravelTap.respawnsSince(approach);
        int positions = TravelTap.positionsSince(approach);
        boolean loading = TravelTap.loadingScreenShown();
        boolean replaced = client.computeOnClient(minecraft -> System.identityHashCode(minecraft.player)) != player;
        String timing = frameTiming(crossing);
        String approaching = approachTiming(crossing);
        client.runOnClient(minecraft -> TravelTap.reset());
        String path = respawns > 0 ? "respawn" : positions > 0 ? "corrected" : "seamless";
        LOGGER.info("[{}] crossed {} -> {} by {}: respawns {}, positions {}, loading screen {}, player replaced {}, now at {}; {}; {}", label, before,
            after, path, respawns, positions, loading, replaced, client.computeOnClient(minecraft -> minecraft.player.position()), timing, approaching);
        if (respawns > 0 || positions > 0 || loading || replaced) {
            failures.add(label + " " + path);
        }
    }

    static List<Vec3> centers(MinecraftServer server, BlockPos frame) {
        WormholesModRuntime runtime = runtime(server);
        MinecraftPortal source = runtime.portals().at(server.overworld(), frame);
        MinecraftPortal destination = runtime.portals().get(source.getDestinationId());
        return List.of(center(source), center(destination));
    }

    private static Vec3 center(MinecraftPortal portal) {
        return new Vec3(portal.getOrigin().x(), portal.getOrigin().y(), portal.getOrigin().z());
    }

    private static String travelState(Minecraft minecraft) {
        ClientPreparedTravel travel = WormholesClient.instance().preparedTravel();
        return "active " + travel.active() + " adopted " + travel.adopted() + " confirmed " + travel.positionConfirmed() + " ready "
            + travel.readyRevision() + " pending " + travel.pendingCrossing() + " armed " + travel.seamless().armed() + " seamless pending "
            + travel.seamless().pending() + " staged "
            + (travel.level() == null ? "none" : travel.level().dimension().identifier() + (travel.level() == minecraft.level ? " (current)" : ""));
    }

    private static void face(SeamlessClient client, Vec3 target) {
        Vec3 feet = client.computeOnClient(minecraft -> minecraft.player.position());
        client.lookAt((float) Math.toDegrees(Math.atan2(-(target.x - feet.x), target.z - feet.z)), 0.0F);
        client.waitTicks(1);
    }

    private static String prepare(SeamlessClient client) {
        String unprepared = client.computeOnClient(minecraft -> WormholesClient.instance().preparedTravel().seamless().unprepared());
        for (int tick = 0; tick < PREPARE_TIMEOUT_TICKS && unprepared != null; tick++) {
            client.waitTicks(1);
            unprepared = client.computeOnClient(minecraft -> WormholesClient.instance().preparedTravel().seamless().unprepared());
        }
        return unprepared;
    }

    private static String frameTiming(int crossing) {
        List<TravelTap.Frame> frames = TravelTap.frames();
        if (crossing < 2 || frames.size() < crossing + SETTLED_FRAME + 2) {
            return "frame timing unavailable";
        }
        long source = median(frames, Math.max(0, crossing - BASELINE_FRAMES), crossing);
        long destination = median(frames, crossing + SETTLED_FRAME, Math.min(frames.size(), crossing + SETTLED_FRAME + BASELINE_FRAMES));
        long worst = 0L;
        int worstFrame = 0;
        for (int index = crossing - 1; index < Math.min(frames.size(), crossing + SETTLED_FRAME); index++) {
            if (frames.get(index).tickNanos() > worst) {
                worst = frames.get(index).tickNanos();
                worstFrame = index - crossing;
            }
        }
        long baseline = Math.max(source, destination);
        return String.format("source median %.2f ms, destination median %.2f ms, worst crossing frame %.2f ms at crossing%+d (%.1fx)",
            source / 1.0E6D, destination / 1.0E6D, worst / 1.0E6D, worstFrame, baseline == 0L ? 0.0D : (double) worst / baseline);
    }

    private static String approachTiming(int crossing) {
        List<TravelTap.Frame> frames = TravelTap.frames();
        int end = Math.min(frames.size(), crossing - 1);
        if (end < BASELINE_FRAMES) {
            return "approach timing unavailable";
        }
        long median = median(frames, 1, end);
        long worst = 0L;
        int worstFrame = 0;
        int slow = 0;
        for (int index = 1; index < end; index++) {
            long nanos = frames.get(index).tickNanos();
            if (nanos > worst) {
                worst = nanos;
                worstFrame = index;
            }
            if (median > 0L && nanos > median * 2L) {
                slow++;
            }
        }
        return String.format("approach %d frames, median %.2f ms, worst %.2f ms at frame %d (%.1fx), %d frames above 2x", end, median / 1.0E6D,
            worst / 1.0E6D, worstFrame, median == 0L ? 0.0D : (double) worst / median, slow);
    }

    private static long median(List<TravelTap.Frame> frames, int from, int to) {
        long[] values = new long[to - from];
        for (int index = from; index < to; index++) {
            values[index - from] = frames.get(index).tickNanos();
        }
        Arrays.sort(values);
        return values.length == 0 ? 0L : values[values.length / 2];
    }

    private static void fill(ServerLevel level, BlockPos min, int sizeX, int sizeY, int sizeZ, BlockState state) {
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    level.setBlockAndUpdate(min.offset(x, y, z), state);
                }
            }
        }
    }
}
