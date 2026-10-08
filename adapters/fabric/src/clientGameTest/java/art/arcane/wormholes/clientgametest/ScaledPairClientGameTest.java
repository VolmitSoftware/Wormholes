package art.arcane.wormholes.clientgametest;

import art.arcane.optics.crossing.ScaleRule;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftScaleAccess;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.transit.OrientationPolicy;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.DyeColor;
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
import java.util.concurrent.atomic.AtomicReference;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;

public final class ScaledPairClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final Square SMALL = new Square(new BlockPos(400, 100, 400), 3);
    private static final Square LARGE = new Square(new BlockPos(440, 100, 400), 9);
    private static final int BACKDROP_Z = 392;
    private static final int BAND = 3;
    private static final double RATIO_FACTOR = 3.0D;
    private static final double APPROACH = 6.0D;
    private static final double ENTRY_OFFSET = 0.5D;
    private static final double EDGE_SAMPLE = 1.2D;
    private static final double OUTSIDE_SAMPLE = 2.5D;
    private static final double FACTOR_TOLERANCE = 1.0E-6D;
    private static final double OFFSET_TOLERANCE = 0.05D;
    private static final double CROSSING_JUMP = 4.0D;
    private static final double DOMINANCE = 1.15D;
    private static final int NEGOTIATION_TIMEOUT_TICKS = 400;
    private static final int ARRIVE_TIMEOUT_TICKS = 200;
    private static final int STREAM_TIMEOUT_TICKS = 600;
    private static final int PREPARE_TIMEOUT_TICKS = 600;
    private static final int CROSSING_TIMEOUT_TICKS = 160;
    private static final int CAPTURE_TIMEOUT_TICKS = 40;
    private static final int PROBE_ATTEMPTS = 60;
    private static final int PROBE_INTERVAL_TICKS = 10;
    private static final int SAMPLE_RADIUS = 2;
    private static final int SETTLE_TICKS = 30;
    private static final int OFF_SCREEN = 0;
    private static final ScaleRule RATIO = ScaleRule.ratio(0.25D, 4.0D);
    private static final BlockState LIME = Blocks.CONCRETE.pick(DyeColor.LIME).defaultBlockState();
    private static final BlockState MAGENTA = Blocks.CONCRETE.pick(DyeColor.MAGENTA).defaultBlockState();
    private static final BlockState YELLOW = Blocks.CONCRETE.pick(DyeColor.YELLOW).defaultBlockState();

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(true);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            run(context, singleplayer.getConnection(), singleplayer.getServer(), "singleplayer");
        }
    }

    private void run(ClientGameTestContext context, TestServerConnection connection, TestServerContext server, String label) {
        connection.waitForChunksDownload();
        context.waitFor(client -> WormholesClient.instance() != null && WormholesClient.instance().session().active(), NEGOTIATION_TIMEOUT_TICKS);
        ServerPlayer player = server.computeOnServer(minecraftServer -> connection.getServerPlayer());
        UUID[] portals = server.computeOnServer(minecraftServer -> build(minecraftServer, player));
        List<String> failures = new ArrayList<>();
        try {
            view(context, connection, server, player, label + " scaled view", failures);
            place(context, server, player, SMALL.approach(ENTRY_OFFSET), 180.0F);
            List<TravelTap.Frame> grown = walk(context, portals[0], label + " ratio");
            crossed(context, grown, RATIO_FACTOR, SMALL, LARGE, label + " ratio", failures);
            context.getInput().holdKeyFor(options -> options.keyUp, 6);
            context.getInput().lookAt(0.0F, 0.0F);
            context.waitTicks(SETTLE_TICKS);
            List<TravelTap.Frame> shrunk = walk(context, portals[1], label + " ratio return");
            crossed(context, shrunk, 1.0D, LARGE, SMALL, label + " ratio return", failures);
            server.runOnServer(minecraftServer -> {
                rule(minecraftServer, player, portals[0], ScaleRule.motion());
                rule(minecraftServer, player, portals[1], ScaleRule.motion());
            });
            place(context, server, player, SMALL.approach(ENTRY_OFFSET), 180.0F);
            List<TravelTap.Frame> moved = walk(context, portals[0], label + " motion");
            crossed(context, moved, 1.0D, SMALL, LARGE, label + " motion", failures);
        } finally {
            context.runOnClient(client -> hud(client, false));
            server.runOnServer(minecraftServer -> {
                MinecraftScaleAccess.scaleAttribute().reset(player);
                runtime(minecraftServer).portals().remove(player, portals[0]);
                runtime(minecraftServer).portals().remove(player, portals[1]);
            });
        }
        assertTrue(failures.isEmpty(), label + ": " + failures);
    }

    private static void view(ClientGameTestContext context, TestServerConnection connection, TestServerContext server, ServerPlayer player,
                             String label, List<String> failures) {
        place(context, server, player, SMALL.approach(0.0D), 180.0F);
        connection.waitForChunksRender();
        BlockPos marker = new BlockPos(LARGE.min().getX() + LARGE.edge() / 2, LARGE.min().getY() + LARGE.edge() / 2, BACKDROP_Z);
        context.waitFor(client -> {
            int key = NativeClientViewAssertions.portalKey(SMALL.min());
            return NativeClientViewAssertions.ready(key) && YELLOW.equals(NativeClientViewAssertions.state(key, marker));
        }, STREAM_TIMEOUT_TICKS);
        float scale = context.computeOnClient(client -> {
            EnvironmentState environment = WormholesClient.instance().session().environment(NativeClientViewAssertions.portalKey(SMALL.min()));
            return environment == null ? Float.NaN : environment.scale();
        });
        LOGGER.info("[{}] the 3x3 view streams at scale {}", label, scale);
        if (Math.abs(scale - 1.0D / RATIO_FACTOR) > FACTOR_TOLERANCE) {
            failures.add(label + ": the 3x3 view environment scale is " + scale + " instead of 1/3");
        }
        context.runOnClient(client -> hud(client, true));
        context.waitTicks(SETTLE_TICKS);
        Vec3 middle = SMALL.center();
        Vec3 edge = middle.add(EDGE_SAMPLE, 0.0D, 0.0D);
        Vec3 outside = middle.add(-OUTSIDE_SAMPLE, 0.0D, 0.0D);
        String failure = null;
        for (int attempt = 0; attempt < PROBE_ATTEMPTS; attempt++) {
            AtomicReference<FramebufferProbe> captured = new AtomicReference<>();
            context.runOnClient(client -> FramebufferProbe.capture(client, captured::set));
            context.waitFor(client -> captured.get() != null, CAPTURE_TIMEOUT_TICKS);
            int[] colors = context.computeOnClient(client -> new int[] {sample(client, captured.get(), middle), sample(client, captured.get(), edge),
                sample(client, captured.get(), outside)});
            if (colors[0] == OFF_SCREEN || colors[1] == OFF_SCREEN || colors[2] == OFF_SCREEN) {
                failure = "a sample point is off screen";
                break;
            }
            LOGGER.info("[{}] attempt {}: center #{} (expected yellow), aperture edge #{} (expected lime), outside #{} (expected magenta)", label,
                attempt, hex(colors[0]), hex(colors[1]), hex(colors[2]));
            failure = yellow(colors[0]) && lime(colors[1]) && magenta(colors[2]) ? null
                : "center #" + hex(colors[0]) + ", aperture edge #" + hex(colors[1]) + ", outside #" + hex(colors[2]);
            if (failure == null) {
                break;
            }
            context.waitTicks(PROBE_INTERVAL_TICKS);
        }
        context.takeScreenshot("scaled-pair-view");
        context.runOnClient(client -> hud(client, false));
        if (failure != null) {
            failures.add(label + ": " + failure);
        }
    }

    private static void crossed(ClientGameTestContext context, List<TravelTap.Frame> frames, double expected, Square from, Square to, String label,
                                List<String> failures) {
        int crossing = context.computeOnClient(client -> TravelTap.crossingFrame(CROSSING_JUMP));
        if (crossing < 1) {
            failures.add(label + ": never crossed");
            return;
        }
        TravelTap.Frame before = frames.get(crossing - 1);
        TravelTap.Frame after = frames.get(crossing);
        int corrections = context.computeOnClient(client -> TravelTap.positions() + TravelTap.respawns());
        if (corrections > 0) {
            failures.add(label + ": the crossing was corrected by " + corrections + " position or respawn packets; " + TravelTap.events());
        }
        double entry = before.camera().x - from.center().x;
        double arrival = after.camera().x - to.center().x;
        double ratio = (double) to.edge() / from.edge();
        List<TravelTap.ScalePacket> packets = context.computeOnClient(client -> TravelTap.scalePackets());
        double settled = context.computeOnClient(client -> client.player.getAttributeValue(Attributes.SCALE));
        AttributeModifier modifier = context.computeOnClient(client -> client.player.getAttribute(Attributes.SCALE).getModifier(MinecraftScaleAccess.ID));
        LOGGER.info("[{}] crossing frame {}: scale {} before, {} on the crossing frame, {} settled; entry offset {}, arrival offset {}; "
                + "server scale packets {}; modifier {}", label, crossing, before.scale(), after.scale(), settled, String.format("%.4f", entry),
            String.format("%.4f", arrival), packets, modifier);
        if (Math.abs(after.scale() - expected) > FACTOR_TOLERANCE) {
            failures.add(label + ": the crossing frame scale is " + after.scale() + " instead of " + expected);
        }
        if (Math.abs(settled - expected) > FACTOR_TOLERANCE) {
            failures.add(label + ": the settled scale is " + settled + " instead of " + expected);
        }
        if (Math.abs(arrival - entry * ratio) > OFFSET_TOLERANCE) {
            failures.add(label + ": the arrival offset " + arrival + " is not " + ratio + " times the entry offset " + entry);
        }
        if (Math.abs(expected - before.scale()) > FACTOR_TOLERANCE) {
            predicted(packets, crossing, expected, label, failures);
            boolean present = Math.abs(expected - 1.0D) > FACTOR_TOLERANCE;
            if (present != (modifier != null)) {
                failures.add(label + ": the portal modifier is " + modifier + " after settling at scale " + expected);
            }
        }
        for (int index = 0; index < frames.size(); index++) {
            double scale = frames.get(index).scale();
            double allowed = index < crossing ? before.scale() : expected;
            if (Math.abs(scale - allowed) > FACTOR_TOLERANCE) {
                failures.add(label + ": frame " + index + " drew at scale " + scale + " instead of " + allowed);
                return;
            }
        }
    }

    private static void predicted(List<TravelTap.ScalePacket> packets, int crossing, double expected, String label, List<String> failures) {
        for (TravelTap.ScalePacket packet : packets) {
            if (packet.frame() < crossing) {
                continue;
            }
            if (Math.abs(packet.before() - expected) > FACTOR_TOLERANCE) {
                failures.add(label + ": the server's scale packet arrived while the client was still at " + packet.before());
            }
            return;
        }
        failures.add(label + ": the server never synced the scale after the crossing");
    }

    private static List<TravelTap.Frame> walk(ClientGameTestContext context, UUID source, String label) {
        for (int tick = 0; tick < PREPARE_TIMEOUT_TICKS; tick++) {
            boolean ready = context.computeOnClient(client -> WormholesClient.instance().preparedTravel().seamless().armed(source)
                && WormholesClient.instance().preparedTravel().seamless().unprepared() == null);
            if (ready) {
                break;
            }
            context.waitTicks(1);
        }
        LOGGER.info("[{}] walking with travel {}", label, context.computeOnClient(client -> WormholesClient.instance().preparedTravel().seamless().unprepared()));
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

    private static void place(ClientGameTestContext context, TestServerContext server, ServerPlayer player, Vec3 position, float yaw) {
        server.runOnServer(minecraftServer -> {
            player.setGameMode(GameType.CREATIVE);
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
            player.teleportTo(player.level(), position.x, position.y, position.z, Set.of(), yaw, 0.0F, false);
            player.setDeltaMovement(Vec3.ZERO);
        });
        context.waitFor(client -> client.player.position().distanceTo(position) < 0.05D, ARRIVE_TIMEOUT_TICKS);
        context.getInput().lookAt(yaw, 0.0F);
        context.waitTicks(SETTLE_TICKS);
    }

    private static UUID[] build(MinecraftServer server, ServerPlayer actor) {
        WormholesModRuntime runtime = runtime(server);
        ServerLevel level = server.overworld();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockPos smallMin = SMALL.min();
        BlockPos largeMin = LARGE.min();
        fill(level, new BlockPos(smallMin.getX() - 6, smallMin.getY() - 1, BACKDROP_Z - 4), 15, 14, 25, air);
        fill(level, new BlockPos(smallMin.getX() - 6, smallMin.getY() - 1, BACKDROP_Z - 4), 15, 1, 25, stone);
        fill(level, new BlockPos(smallMin.getX() - 6, smallMin.getY(), BACKDROP_Z), 15, 13, 1, MAGENTA);
        fill(level, new BlockPos(largeMin.getX() - 8, largeMin.getY() - 1, BACKDROP_Z - 4), 25, 20, 25, air);
        fill(level, new BlockPos(largeMin.getX() - 8, largeMin.getY() - 1, BACKDROP_Z - 4), 25, 1, 25, stone);
        int middle = largeMin.getX() + LARGE.edge() / 2;
        for (int x = largeMin.getX() - 8; x < largeMin.getX() + 17; x++) {
            fill(level, new BlockPos(x, largeMin.getY(), BACKDROP_Z), 1, 19, 1, Math.abs(x - middle) <= BAND ? YELLOW : LIME);
        }
        MinecraftPortal small = portal(runtime, actor, level, SMALL);
        MinecraftPortal large = portal(runtime, actor, level, LARGE);
        assertTrue(runtime.portals().link(actor, small.getId(), large.getId()), "3x3 link rejected");
        assertTrue(runtime.portals().link(actor, large.getId(), small.getId()), "9x9 link rejected");
        rule(server, actor, small.getId(), RATIO);
        rule(server, actor, large.getId(), RATIO);
        return new UUID[] {small.getId(), large.getId()};
    }

    private static MinecraftPortal portal(WormholesModRuntime runtime, ServerPlayer actor, ServerLevel level, Square square) {
        List<BlockPos> cells = new ArrayList<>(square.edge() * square.edge());
        for (int x = 0; x < square.edge(); x++) {
            for (int y = 0; y < square.edge(); y++) {
                cells.add(square.min().offset(x, y, 0));
            }
        }
        MinecraftPortal portal = runtime.portals().create(actor.getUUID(), level, cells, PortalType.PORTAL, new Vec3(0, 0, -1));
        assertTrue(portal != null, "portal creation rejected at " + square.min());
        portal.setAmbientStyle(AmbientParticleStyle.OFF);
        portal.setOrientation(OrientationPolicy.FRAME);
        return portal;
    }

    private static void rule(MinecraftServer server, ServerPlayer actor, UUID portal, ScaleRule rule) {
        assertTrue(runtime(server).portals().update(actor, portal, target -> target.setScaleRule(rule)), "scale rule " + rule + " rejected");
    }

    private static int sample(Minecraft client, FramebufferProbe probe, Vec3 world) {
        int[] pixel = FramebufferProbe.screen(client, world);
        return pixel == null ? OFF_SCREEN : probe.average(pixel[0], pixel[1], SAMPLE_RADIUS);
    }

    private static boolean lime(int color) {
        return green(color) > red(color) * DOMINANCE && green(color) > blue(color) * DOMINANCE;
    }

    private static boolean magenta(int color) {
        return red(color) > green(color) * DOMINANCE && blue(color) > green(color) * DOMINANCE;
    }

    private static boolean yellow(int color) {
        return red(color) > blue(color) * DOMINANCE && green(color) > blue(color) * DOMINANCE && !lime(color);
    }

    private static String hex(int color) {
        return Integer.toHexString(color & 0xFFFFFF);
    }

    private static int red(int color) {
        return (color >> 16) & 0xFF;
    }

    private static int green(int color) {
        return (color >> 8) & 0xFF;
    }

    private static int blue(int color) {
        return color & 0xFF;
    }

    private static void hud(Minecraft client, boolean hidden) {
        if (client.gui.hud.isHidden() != hidden) {
            client.gui.hud.toggle();
        }
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

    private record Square(BlockPos min, int edge) {
        Vec3 center() {
            return new Vec3(min.getX() + edge / 2.0D, min.getY() + edge / 2.0D, min.getZ() + 0.5D);
        }

        Vec3 approach(double offset) {
            return new Vec3(min.getX() + edge / 2.0D + offset, min.getY(), min.getZ() + 0.5D + APPROACH);
        }
    }
}
