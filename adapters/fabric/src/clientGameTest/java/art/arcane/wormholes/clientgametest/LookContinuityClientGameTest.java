package art.arcane.wormholes.clientgametest;

import art.arcane.optics.crossing.ArrivalOrientation;
import art.arcane.optics.crossing.LookTransfer;
import art.arcane.optics.crossing.OrientationRule;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.transit.OrientationPolicy;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;

public final class LookContinuityClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final BlockPos POLE_SOURCE = new BlockPos(200, 100, 200);
    private static final BlockPos UPWARD = new BlockPos(220, 100, 200);
    private static final BlockPos WALL_SOURCE = new BlockPos(240, 100, 200);
    private static final BlockPos WALL = new BlockPos(260, 100, 203);
    private static final BlockPos TWIST_SOURCE = new BlockPos(280, 100, 200);
    private static final BlockPos TWIST_DESTINATION = new BlockPos(320, 100, 200);
    private static final float POLE_YAW = 30.0F;
    private static final float WALL_YAW = 0.0F;
    private static final double HOVER = 6.0D;
    private static final double APPROACH = 6.5D;
    private static final double CROSSING_JUMP = 4.0D;
    private static final float ANGLE_TOLERANCE = 0.5F;
    private static final double VECTOR_TOLERANCE = 1.0E-3D;
    private static final double EXIT_TOLERANCE = 0.02D;
    private static final float ROLL_SETTLED = 0.1F;
    private static final float ROLL_START_SHARE = 0.6F;
    private static final double ROLL_EASE_SECONDS = 0.35D;
    private static final long ROLL_DEADLINE_NANOS = 500_000_000L;
    private static final int NEGOTIATION_TIMEOUT_TICKS = 400;
    private static final int ARRIVE_TIMEOUT_TICKS = 200;
    private static final int PREPARE_TIMEOUT_TICKS = 600;
    private static final int CROSSING_TIMEOUT_TICKS = 160;
    private static final int SETTLE_TICKS = 30;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(true);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            run(context, singleplayer.getConnection(), singleplayer.getServer(), "singleplayer");
        }
        try (TestDedicatedServerContext server = context.worldBuilder().createServer(ClientViewTestConfig.serverProperties());
             TestDedicatedServerConnection connection = server.connect()) {
            run(context, connection, server, "dedicated");
        }
    }

    private void run(ClientGameTestContext context, TestServerConnection connection, TestServerContext server, String label) {
        connection.waitForChunksDownload();
        context.waitFor(client -> WormholesClient.instance() != null && WormholesClient.instance().session().active(), NEGOTIATION_TIMEOUT_TICKS);
        ServerPlayer player = server.computeOnServer(minecraftServer -> connection.getServerPlayer());
        Layout layout = server.computeOnServer(minecraftServer -> build(minecraftServer, player));
        List<String> failures = new ArrayList<>();
        try {
            pole(context, connection, server, player, layout, label + " straight down", failures);
            floorToWall(context, connection, server, player, layout, label + " floor to wall", failures);
            twist(context, connection, server, player, layout, label + " twisted pair", failures);
        } finally {
            context.runOnClient(client -> WormholesClient.instance().config().cameraRollEaseSeconds = ROLL_EASE_SECONDS);
            server.runOnServer(minecraftServer -> {
                for (UUID portal : layout.portals()) {
                    runtime(minecraftServer).portals().remove(player, portal);
                }
            });
        }
        assertTrue(failures.isEmpty(), label + ": " + failures);
    }

    private static void pole(ClientGameTestContext context, TestServerConnection connection, TestServerContext server, ServerPlayer player,
                             Layout layout, String label, List<String> failures) {
        List<TravelTap.Frame> frames = fall(context, connection, server, player, layout.poleSource(), POLE_SOURCE, POLE_YAW, label);
        seamless(context, label, failures);
        int crossing = TravelTap.crossingFrame(CROSSING_JUMP);
        if (crossing < 1) {
            failures.add(label + ": never crossed");
            return;
        }
        TravelTap.Frame after = frames.get(crossing);
        LOGGER.info("[{}] arrived at yaw {} pitch {}, look transfer {}", label, after.yaw(), after.pitch(), layout.poleLook());
        if (Math.abs(after.pitch() + 90.0F) > ANGLE_TOLERANCE) {
            failures.add(label + ": arrival pitch " + after.pitch() + " is not straight up");
        }
        if (Math.abs(Angles.unwrap(after.yaw() - layout.poleLook().yaw(), 0.0F)) > ANGLE_TOLERANCE) {
            failures.add(label + ": arrival yaw " + after.yaw() + " differs from the look transfer " + layout.poleLook().yaw());
        }
        continuity(frames, crossing, layout.poleToward(), true, label, failures);
    }

    private static void floorToWall(ClientGameTestContext context, TestServerConnection connection, TestServerContext server, ServerPlayer player,
                                    Layout layout, String label, List<String> failures) {
        List<TravelTap.Frame> frames = fall(context, connection, server, player, layout.wallSource(), WALL_SOURCE, WALL_YAW, label);
        seamless(context, label, failures);
        int crossing = TravelTap.crossingFrame(CROSSING_JUMP);
        if (crossing < 1) {
            failures.add(label + ": never crossed");
            return;
        }
        TravelTap.Frame after = frames.get(crossing);
        Vec3 exit = vector(Angles.direction(layout.wallLook().yaw(), layout.wallLook().pitch()));
        LOGGER.info("[{}] arrived at yaw {} pitch {} looking {}, exit {}, look transfer {}", label, after.yaw(), after.pitch(), after.forward(), exit,
            layout.wallLook());
        if (Math.abs(after.pitch()) > ANGLE_TOLERANCE) {
            failures.add(label + ": a straight-down look arrived at pitch " + after.pitch());
        }
        if (after.forward().subtract(exit).length() > EXIT_TOLERANCE) {
            failures.add(label + ": the arrival looks along " + after.forward() + " instead of the exit " + exit);
        }
        continuity(frames, crossing, layout.wallToward(), false, label, failures);
    }

    private static void twist(ClientGameTestContext context, TestServerConnection connection, TestServerContext server, ServerPlayer player,
                              Layout layout, String label, List<String> failures) {
        Vec3 start = new Vec3(TWIST_SOURCE.getX() + 1.5D, TWIST_SOURCE.getY() - 0.5D, TWIST_SOURCE.getZ() + APPROACH);
        place(context, server, player, start, 180.0F, 0.0F, false);
        context.runOnClient(client -> WormholesClient.instance().config().cameraRollEaseSeconds = ROLL_EASE_SECONDS);
        List<TravelTap.Frame> eased = walk(context, layout.twistSource(), label + " eased");
        seamless(context, label + " eased", failures);
        int crossing = TravelTap.crossingFrame(CROSSING_JUMP);
        if (crossing < 1) {
            failures.add(label + " eased: never crossed");
            return;
        }
        eased(eased, crossing, Math.abs(layout.twistLook().roll()), label + " eased", failures);
        continuity(eased, crossing, layout.twistToward(), false, label + " eased", failures);
        context.getInput().holdKeyFor(options -> options.keyUp, 6);
        Vec3 feet = context.computeOnClient(client -> client.player.position());
        Vec3 portal = Vec3.atLowerCornerOf(TWIST_DESTINATION).add(1.5D, 1.5D, 0.5D);
        LOGGER.info("[{}] stepped away to {} after arriving at yaw {} pitch {}", label, feet, eased.get(crossing).yaw(), eased.get(crossing).pitch());
        context.getInput().lookAt((float) Math.toDegrees(Math.atan2(-(portal.x - feet.x), portal.z - feet.z)), 0.0F);
        context.waitTicks(SETTLE_TICKS);
        context.runOnClient(client -> WormholesClient.instance().config().cameraRollEaseSeconds = 0.0D);
        List<TravelTap.Frame> instant = walk(context, layout.twistDestination(), label + " instant");
        seamless(context, label + " instant", failures);
        int back = TravelTap.crossingFrame(CROSSING_JUMP);
        if (back < 1) {
            failures.add(label + " instant: never crossed back");
            return;
        }
        float worst = 0.0F;
        for (int index = back; index < instant.size(); index++) {
            worst = Math.max(worst, Math.abs(roll(instant.get(index))));
        }
        LOGGER.info("[{}] return crossing with the ease disabled: worst roll {} degrees over {} frames", label, worst, instant.size() - back);
        if (worst > ROLL_SETTLED) {
            failures.add(label + " instant: the camera rolled " + worst + " degrees with the ease disabled");
        }
    }

    private static List<TravelTap.Frame> fall(ClientGameTestContext context, TestServerConnection connection, TestServerContext server,
                                              ServerPlayer player, UUID source, BlockPos min, float yaw, String label) {
        Vec3 hover = new Vec3(min.getX() + 1.5D, min.getY() + HOVER, min.getZ() + 1.5D);
        place(context, server, player, hover, yaw, 90.0F, true);
        connection.waitForChunksRender();
        prepare(context, source, label);
        context.getInput().lookAt(yaw, 90.0F);
        context.waitTicks(1);
        context.runOnClient(client -> TravelTap.reset());
        server.runOnServer(minecraftServer -> {
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
        });
        for (int tick = 0; tick < CROSSING_TIMEOUT_TICKS && context.computeOnClient(client -> TravelTap.crossingFrame(CROSSING_JUMP)) < 0; tick++) {
            context.waitTicks(1);
        }
        context.waitTicks(SETTLE_TICKS);
        return context.computeOnClient(client -> TravelTap.frames());
    }

    private static List<TravelTap.Frame> walk(ClientGameTestContext context, UUID source, String label) {
        prepare(context, source, label);
        context.runOnClient(client -> TravelTap.reset());
        context.getInput().holdKey(options -> options.keyUp);
        try {
            for (int tick = 0; tick < CROSSING_TIMEOUT_TICKS && context.computeOnClient(client -> TravelTap.crossingFrame(CROSSING_JUMP)) < 0; tick++) {
                context.waitTicks(1);
            }
        } finally {
            context.getInput().releaseKey(options -> options.keyUp);
        }
        context.waitTicks(SETTLE_TICKS);
        return context.computeOnClient(client -> TravelTap.frames());
    }

    private static void prepare(ClientGameTestContext context, UUID source, String label) {
        for (int tick = 0; tick < PREPARE_TIMEOUT_TICKS; tick++) {
            boolean ready = context.computeOnClient(client -> WormholesClient.instance().preparedTravel().seamless().armed(source)
                && WormholesClient.instance().preparedTravel().seamless().unprepared() == null);
            if (ready) {
                return;
            }
            context.waitTicks(1);
        }
        LOGGER.info("[{}] crossing before the travel was prepared: {}", label,
            context.computeOnClient(client -> WormholesClient.instance().preparedTravel().seamless().unprepared()));
    }

    private static void place(ClientGameTestContext context, TestServerContext server, ServerPlayer player, Vec3 position, float yaw, float pitch,
                              boolean flying) {
        server.runOnServer(minecraftServer -> {
            player.setGameMode(GameType.CREATIVE);
            player.getAbilities().mayfly = true;
            player.getAbilities().flying = flying;
            player.onUpdateAbilities();
            player.teleportTo(player.level(), position.x, position.y, position.z, Set.of(), yaw, pitch, false);
            player.setDeltaMovement(Vec3.ZERO);
        });
        context.waitFor(client -> client.player.position().distanceTo(position) < 0.05D, ARRIVE_TIMEOUT_TICKS);
        context.getInput().lookAt(yaw, pitch);
        context.waitTicks(SETTLE_TICKS);
    }

    private static void seamless(ClientGameTestContext context, String label, List<String> failures) {
        int corrections = context.computeOnClient(client -> TravelTap.positions() + TravelTap.respawns());
        if (corrections > 0) {
            failures.add(label + ": the crossing was corrected by " + corrections + " position or respawn packets; " + TravelTap.events());
        }
    }

    private static void continuity(List<TravelTap.Frame> frames, int crossing, OpticTransform toward, boolean checkUp, String label,
                                   List<String> failures) {
        double worstForward = 0.0D;
        double worstUp = 0.0D;
        int worstFrame = -1;
        for (int index = 1; index < frames.size(); index++) {
            TravelTap.Frame before = frames.get(index - 1);
            TravelTap.Frame after = frames.get(index);
            Vec3 forward = index == crossing ? map(toward, before.forward()) : before.forward();
            Vec3 up = index == crossing ? map(toward, before.up()) : before.up();
            double forwardGap = angle(forward, after.forward());
            double upGap = checkUp ? angle(up, after.up()) : 0.0D;
            if (forwardGap > worstForward || upGap > worstUp) {
                worstFrame = index;
            }
            worstForward = Math.max(worstForward, forwardGap);
            worstUp = Math.max(worstUp, upGap);
        }
        LOGGER.info("[{}] camera continuity over {} frames: worst forward turn {} rad, worst up turn {} rad at frame {} (crossing {})", label,
            frames.size(), String.format("%.6f", worstForward), String.format("%.6f", worstUp), worstFrame, crossing);
        if (worstForward > VECTOR_TOLERANCE) {
            failures.add(label + ": the camera forward jumped " + worstForward + " rad at frame " + worstFrame + " (crossing " + crossing + ")");
        }
        if (worstUp > VECTOR_TOLERANCE) {
            failures.add(label + ": the camera up jumped " + worstUp + " rad at frame " + worstFrame + " (crossing " + crossing + ")");
        }
    }

    private static void eased(List<TravelTap.Frame> frames, int crossing, float expected, String label, List<String> failures) {
        float first = Math.abs(roll(frames.get(crossing)));
        float previous = first;
        long settled = -1L;
        int rises = 0;
        List<String> samples = new ArrayList<>();
        for (int index = crossing; index < frames.size(); index++) {
            float roll = Math.abs(roll(frames.get(index)));
            samples.add(String.format("%.2f", roll));
            if (roll > previous + 1.0E-3F) {
                rises++;
            }
            if (settled < 0L && roll < ROLL_SETTLED) {
                settled = frames.get(index).wallNanos() - frames.get(crossing).wallNanos();
            }
            previous = roll;
        }
        LOGGER.info("[{}] crossing roll {} degrees (look transfer roll {}), settled after {} ms, {} rises; {}", label, first, expected,
            settled < 0L ? "never" : String.format("%.1f", settled / 1.0E6D), rises, samples);
        if (expected < 45.0F) {
            failures.add(label + ": the twisted pair carries only " + expected + " degrees of roll");
        }
        if (first < expected * ROLL_START_SHARE) {
            failures.add(label + ": the crossing frame rolled only " + first + " of " + expected + " degrees");
        }
        if (rises > 0) {
            failures.add(label + ": the roll grew " + rises + " times while easing out");
        }
        if (settled < 0L || settled > ROLL_DEADLINE_NANOS) {
            failures.add(label + ": the roll did not settle below " + ROLL_SETTLED + " degrees within 0.5 s");
        }
    }

    private static Layout build(MinecraftServer server, ServerPlayer actor) {
        WormholesModRuntime runtime = runtime(server);
        ServerLevel level = server.overworld();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState glass = Blocks.GLASS.defaultBlockState();
        BlockState slab = Blocks.SMOOTH_STONE_SLAB.defaultBlockState();
        fill(level, POLE_SOURCE.offset(-3, -5, -3), 9, 18, 9, air);
        fill(level, POLE_SOURCE.offset(-3, -5, -3), 9, 1, 9, glass);
        fill(level, UPWARD.offset(-3, -3, -3), 9, 16, 9, air);
        fill(level, UPWARD.offset(-3, -3, -3), 9, 1, 9, glass);
        fill(level, WALL_SOURCE.offset(-3, -5, -3), 9, 18, 9, air);
        fill(level, WALL_SOURCE.offset(-3, -5, -3), 9, 1, 9, glass);
        fill(level, WALL.offset(-6, -3, -13), 15, 14, 27, air);
        fill(level, WALL.offset(-6, -3, -13), 15, 1, 27, Blocks.STONE.defaultBlockState());
        for (BlockPos min : List.of(TWIST_SOURCE, TWIST_DESTINATION)) {
            fill(level, min.offset(-4, -2, -10), 11, 10, 21, air);
            fill(level, min.offset(-4, -2, -10), 11, 1, 21, Blocks.STONE.defaultBlockState());
            fill(level, min.offset(-4, -1, -10), 11, 1, 21, slab);
        }
        MinecraftPortal poleSource = portal(runtime, actor, level, floor(POLE_SOURCE), new Vec3(0, 1, 0));
        MinecraftPortal upward = portal(runtime, actor, level, floor(UPWARD), new Vec3(0, -1, 0));
        MinecraftPortal wallSource = portal(runtime, actor, level, floor(WALL_SOURCE), new Vec3(0, 1, 0));
        MinecraftPortal wall = portal(runtime, actor, level, NativeClientViewAssertions.cells(WALL), new Vec3(0, 0, 1));
        MinecraftPortal twistSource = portal(runtime, actor, level, NativeClientViewAssertions.cells(TWIST_SOURCE), new Vec3(0, 0, -1));
        MinecraftPortal twistDestination = portal(runtime, actor, level, NativeClientViewAssertions.cells(TWIST_DESTINATION), new Vec3(0, 0, -1));
        assertTrue(runtime.portals().update(actor, twistDestination.getId(), portal -> portal.setFrame(portal.getFrame().rotateClockwise())),
            "twisted frame rejected");
        assertTrue(runtime.portals().link(actor, poleSource.getId(), upward.getId()), "pole link rejected");
        assertTrue(runtime.portals().link(actor, wallSource.getId(), wall.getId()), "floor to wall link rejected");
        assertTrue(runtime.portals().link(actor, twistSource.getId(), twistDestination.getId()), "twisted link rejected");
        assertTrue(runtime.portals().link(actor, twistDestination.getId(), twistSource.getId()), "twisted return link rejected");
        Vec3d above = new Vec3d(0.0D, 1.0D, 0.0D);
        Vec3d approach = new Vec3d(0.0D, 0.0D, 1.0D);
        return new Layout(List.of(poleSource.getId(), upward.getId(), wallSource.getId(), wall.getId(), twistSource.getId(), twistDestination.getId()),
            poleSource.getId(), wallSource.getId(), twistSource.getId(), twistDestination.getId(),
            toward(poleSource, upward, above), toward(wallSource, wall, above), toward(twistSource, twistDestination, approach),
            transfer(poleSource, upward, above, POLE_YAW, 90.0F), transfer(wallSource, wall, above, WALL_YAW, 90.0F),
            transfer(twistSource, twistDestination, approach, 180.0F, 0.0F));
    }

    private static MinecraftPortal portal(WormholesModRuntime runtime, ServerPlayer actor, ServerLevel level, List<BlockPos> cells, Vec3 normal) {
        MinecraftPortal portal = runtime.portals().create(actor.getUUID(), level, cells, PortalType.PORTAL, normal);
        assertTrue(portal != null, "portal creation rejected at " + cells.getFirst());
        portal.setAmbientStyle(AmbientParticleStyle.OFF);
        portal.setOrientation(OrientationPolicy.FRAME);
        return portal;
    }

    private static OpticTransform toward(MinecraftPortal source, MinecraftPortal destination, Vec3d approach) {
        boolean front = front(source, approach);
        return OpticTransform.between(source.getFrame().view(front), source.getOrigin(), destination.getFrame().view(front), destination.getOrigin());
    }

    private static LookTransfer transfer(MinecraftPortal source, MinecraftPortal destination, Vec3d approach, float yaw, float pitch) {
        boolean front = front(source, approach);
        PlaneCrossing crossing = new PlaneCrossing(source.getFrame().view(front), source.getOrigin(), source.getOrigin(),
            approach.multiply(-0.4D), Angles.direction(yaw, pitch), front);
        return ArrivalOrientation.transfer(crossing, LookTransfer.cameraUp(yaw, pitch), destination.getFrame(), OrientationRule.FRAME, false);
    }

    private static boolean front(MinecraftPortal portal, Vec3d approach) {
        return approach.x() * portal.getFrame().getNormal().x() + approach.y() * portal.getFrame().getNormal().y()
            + approach.z() * portal.getFrame().getNormal().z() > 0.0D;
    }

    private static float roll(TravelTap.Frame frame) {
        Vec3 canonical = vector(LookTransfer.cameraUp(frame.yaw(), frame.pitch()));
        Vec3 up = frame.up();
        double sine = canonical.cross(up).dot(frame.forward());
        double cosine = canonical.dot(up);
        return (float) Math.toDegrees(Math.atan2(sine, cosine));
    }

    private static Vec3 map(OpticTransform transform, Vec3 vector) {
        return vector(transform.vector(new Vec3d(vector.x, vector.y, vector.z)));
    }

    private static Vec3 vector(Vec3d vector) {
        return new Vec3(vector.x(), vector.y(), vector.z());
    }

    private static double angle(Vec3 expected, Vec3 actual) {
        double cosine = expected.normalize().dot(actual.normalize());
        return Math.acos(Math.max(-1.0D, Math.min(1.0D, cosine)));
    }

    private static List<BlockPos> floor(BlockPos min) {
        List<BlockPos> cells = new ArrayList<>(9);
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                cells.add(min.offset(x, 0, z));
            }
        }
        return cells;
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

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Layout(List<UUID> portals, UUID poleSource, UUID wallSource, UUID twistSource, UUID twistDestination, OpticTransform poleToward,
                          OpticTransform wallToward, OpticTransform twistToward, LookTransfer poleLook, LookTransfer wallLook,
                          LookTransfer twistLook) {
    }
}
