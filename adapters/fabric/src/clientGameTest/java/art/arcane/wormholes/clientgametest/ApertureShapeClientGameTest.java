package art.arcane.wormholes.clientgametest;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.aperture.AperturePolygon;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.PlaneShape;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.optics.shape.ShapeMesh;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.ClientPortal;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.PortalType;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

public final class ApertureShapeClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final int EDGE = 7;
    private static final BlockPos A_MIN = new BlockPos(100, 81, 100);
    private static final BlockPos B_MIN = new BlockPos(100, 81, 160);
    private static final int FLOOR_Y = 75;
    private static final int WALL_OFFSET = 4;
    private static final double VIEW_DISTANCE = 9.0D;
    private static final double RUN_UP = 3.0D;
    private static final double EYE_HEIGHT = 1.62D;
    private static final double OUTLINE_TOLERANCE_CELLS = 0.05D;
    private static final int NEGOTIATION_TIMEOUT_TICKS = 400;
    private static final int STREAM_TIMEOUT_TICKS = 600;
    private static final int SETTLE_TICKS = 10;
    private static final int CAPTURE_TIMEOUT_TICKS = 40;
    private static final int PREPARE_TIMEOUT_TICKS = 600;
    private static final int FLIGHT_TIMEOUT_TICKS = 80;
    private static final int SAMPLE_RADIUS = 2;
    private static final int PROBE_ATTEMPTS = 60;
    private static final int PROBE_INTERVAL_TICKS = 10;
    private static final int OFF_SCREEN = 0;
    private static final double CROSSING_JUMP = 4.0D;
    private static final double DOMINANCE = 1.15D;
    private static final BlockState LIME = Blocks.CONCRETE.pick(DyeColor.LIME).defaultBlockState();
    private static final BlockState MAGENTA = Blocks.CONCRETE.pick(DyeColor.MAGENTA).defaultBlockState();
    private static final ShapeDescriptor CIRCLE = ShapeDescriptor.parse("circle");
    private static final ShapeDescriptor FLOWER = ShapeDescriptor.parse("flower(petals=5)");
    private static final ShapeDescriptor TURNED_CIRCLE = ShapeDescriptor.parse("circle@rotate(45)");

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enable();
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            runScenario(context, singleplayer.getConnection(), singleplayer.getServer(), "singleplayer");
        }
        try (TestDedicatedServerContext server = context.worldBuilder().createServer(ClientViewTestConfig.serverProperties());
             TestDedicatedServerConnection connection = server.connect()) {
            runScenario(context, connection, server, "dedicated");
        }
    }

    private void runScenario(ClientGameTestContext context, TestServerConnection connection, TestServerContext server, String label) {
        connection.waitForChunksDownload();
        context.waitFor(client -> WormholesClient.instance() != null && WormholesClient.instance().session().active(), NEGOTIATION_TIMEOUT_TICKS);
        ServerPlayer player = server.computeOnServer(minecraftServer -> connection.getServerPlayer());
        UUID[] portals = server.computeOnServer(minecraftServer -> build(minecraftServer, player));
        List<String> failures = new ArrayList<>();
        view(context, connection, server, player, A_MIN, CIRCLE, LIME);
        assertMesh(context, A_MIN, failures, label + " circle");
        probe(context, A_MIN, new double[] {0.0D, 0.0D}, cell(0.5D, 0.5D), label + " circle center and corner", failures);
        context.takeScreenshot("aperture-shape-circle-" + label);
        restoreView(context);
        fly(context, server, player, A_MIN, cell(0.5D, 0.5D), false, label + " circle corner flight", failures);
        fly(context, server, player, A_MIN, new double[] {0.0D, 0.0D}, true, label + " circle center flight", failures);
        view(context, connection, server, player, B_MIN, FLOWER, MAGENTA);
        assertMesh(context, B_MIN, failures, label + " flower");
        probe(context, B_MIN, new double[] {0.0D, 0.75D}, new double[] {0.0D, -0.75D}, label + " flower petal and notch", failures);
        context.takeScreenshot("aperture-shape-flower-" + label);
        restoreView(context);
        fly(context, server, player, B_MIN, new double[] {0.0D, -0.75D}, false, label + " flower notch flight", failures);
        fly(context, server, player, B_MIN, new double[] {0.0D, 0.75D}, true, label + " flower petal flight", failures);
        feather(context, connection, server, player, portals[0], label);
        server.runOnServer(minecraftServer -> {
            runtime(minecraftServer).portals().remove(player, portals[0]);
            runtime(minecraftServer).portals().remove(player, portals[1]);
        });
        assertTrue(failures.isEmpty(), label + ": " + failures);
    }

    private static UUID[] build(MinecraftServer server, ServerPlayer actor) {
        WormholesModRuntime runtime = runtime(server);
        ServerLevel level = server.overworld();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
        fill(level, new BlockPos(88, FLOOR_Y + 1, 88), 32, 26, 90, Blocks.AIR.defaultBlockState());
        fill(level, new BlockPos(88, FLOOR_Y, 88), 32, 1, 90, Blocks.STONE.defaultBlockState());
        fill(level, new BlockPos(88, FLOOR_Y + 1, A_MIN.getZ() - WALL_OFFSET), 32, 25, 1, MAGENTA);
        fill(level, new BlockPos(88, FLOOR_Y + 1, B_MIN.getZ() - WALL_OFFSET), 32, 25, 1, LIME);
        MinecraftPortal a = runtime.portals().create(actor.getUUID(), level, cells(A_MIN), PortalType.PORTAL, new Vec3(0, 0, -1));
        MinecraftPortal b = runtime.portals().create(actor.getUUID(), level, cells(B_MIN), PortalType.PORTAL, new Vec3(0, 0, -1));
        a.setAmbientStyle(AmbientParticleStyle.OFF);
        b.setAmbientStyle(AmbientParticleStyle.OFF);
        assertTrue(runtime.portals().link(actor, a.getId(), b.getId()), "circle link rejected");
        assertTrue(runtime.portals().link(actor, b.getId(), a.getId()), "flower link rejected");
        assertTrue(shape(runtime, actor, a.getId(), CIRCLE), "circle shape refused");
        assertTrue(shape(runtime, actor, b.getId(), FLOWER), "flower shape refused");
        actor.setGameMode(GameType.CREATIVE);
        actor.getAbilities().mayfly = true;
        actor.getAbilities().flying = true;
        actor.onUpdateAbilities();
        return new UUID[] {a.getId(), b.getId()};
    }

    private static boolean shape(WormholesModRuntime runtime, ServerPlayer actor, UUID portal, ShapeDescriptor shape) {
        boolean[] accepted = new boolean[1];
        boolean updated = runtime.portals().update(actor, portal, target -> accepted[0] = target.setApertureShape(shape));
        return updated && accepted[0];
    }

    private static void view(ClientGameTestContext context, TestServerConnection connection, TestServerContext server, ServerPlayer player,
                             BlockPos min, ShapeDescriptor shape, BlockState destinationWall) {
        Vec3 center = Vec3.atLowerCornerOf(min).add(EDGE / 2.0D, EDGE / 2.0D, 0.5D);
        teleport(server, player, center.x, center.y - EYE_HEIGHT, center.z + VIEW_DISTANCE);
        connection.waitForChunksRender();
        context.waitFor(client -> {
            int key = NativeClientViewAssertions.portalKey(min);
            ClientPortal portal = key == 0 ? null : WormholesClient.instance().session().portal(key);
            return portal != null && portal.geometry().shape().equals(shape) && NativeClientViewAssertions.ready(key)
                && ClientPortalRenderer.instance().apertureShape(key) != null;
        }, STREAM_TIMEOUT_TICKS);
        BlockPos wall = new BlockPos(min.getX() + EDGE / 2, min.getY() + EDGE / 2, (min == A_MIN ? B_MIN : A_MIN).getZ() - WALL_OFFSET);
        context.waitFor(client -> destinationWall.equals(NativeClientViewAssertions.state(NativeClientViewAssertions.portalKey(min), wall)),
            STREAM_TIMEOUT_TICKS);
        context.runOnClient(client -> hud(client, true));
        context.waitTicks(SETTLE_TICKS);
    }

    private static void restoreView(ClientGameTestContext context) {
        context.runOnClient(client -> hud(client, false));
    }

    private static void assertMesh(ClientGameTestContext context, BlockPos min, List<String> failures, String label) {
        String failure = context.computeOnClient(client -> {
            int key = NativeClientViewAssertions.portalKey(min);
            ShapeMesh mesh = ClientPortalRenderer.instance().apertureShape(key);
            if (mesh == null || mesh.triangleCount() == 0) {
                return "no shaped aperture mesh for portal " + key;
            }
            PlaneShape plane = AperturePolygon.from(WormholesClient.instance().session().portal(key).geometry()).planeShape();
            float[] positions = mesh.positions();
            double worst = Double.NEGATIVE_INFINITY;
            for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
                worst = Math.max(worst, plane.signedDistance(positions[vertex * 2], positions[vertex * 2 + 1]));
            }
            LOGGER.info("[{}] aperture mesh {} triangles, {} vertices, worst vertex distance {} cells", label, mesh.triangleCount(),
                mesh.vertexCount(), worst);
            return worst <= OUTLINE_TOLERANCE_CELLS ? null : "mesh vertex " + worst + " cells outside the shape";
        });
        if (failure != null) {
            failures.add(label + ": " + failure);
        }
    }

    private static void probe(ClientGameTestContext context, BlockPos min, double[] inside, double[] outside, String label, List<String> failures) {
        boolean limeThrough = min == A_MIN;
        String failure = null;
        for (int attempt = 0; attempt < PROBE_ATTEMPTS; attempt++) {
            AtomicReference<FramebufferProbe> captured = new AtomicReference<>();
            context.runOnClient(client -> FramebufferProbe.capture(client, captured::set));
            context.waitFor(client -> captured.get() != null, CAPTURE_TIMEOUT_TICKS);
            int[] colors = context.computeOnClient(client -> new int[] {sample(client, min, captured.get(), inside),
                sample(client, min, captured.get(), outside)});
            if (colors[0] == OFF_SCREEN || colors[1] == OFF_SCREEN) {
                failure = "a sample point is off screen";
                break;
            }
            LOGGER.info("[{}] attempt {}: through the shape #{} (expected {}), outside the shape #{} (expected {})", label, attempt,
                Integer.toHexString(colors[0] & 0xFFFFFF), limeThrough ? "lime" : "magenta", Integer.toHexString(colors[1] & 0xFFFFFF),
                limeThrough ? "magenta" : "lime");
            if (lime(colors[0]) == limeThrough && magenta(colors[0]) != limeThrough) {
                failure = lime(colors[1]) != limeThrough && magenta(colors[1]) == limeThrough ? null
                    : "outside the shape reads #" + Integer.toHexString(colors[1] & 0xFFFFFF) + " instead of the source backdrop";
                break;
            }
            failure = "the destination never appeared through the shape, last read #" + Integer.toHexString(colors[0] & 0xFFFFFF);
            context.waitTicks(PROBE_INTERVAL_TICKS);
        }
        if (failure != null) {
            failures.add(label + ": " + failure);
        }
    }

    private static int sample(Minecraft client, BlockPos min, FramebufferProbe probe, double[] unit) {
        ApertureDescriptor geometry = WormholesClient.instance().session().portal(NativeClientViewAssertions.portalKey(min)).geometry();
        int[] pixel = FramebufferProbe.screen(client, world(geometry, unit));
        return pixel == null ? OFF_SCREEN : probe.average(pixel[0], pixel[1], SAMPLE_RADIUS);
    }

    private static boolean lime(int color) {
        return green(color) > red(color) * DOMINANCE && green(color) > blue(color) * DOMINANCE;
    }

    private static boolean magenta(int color) {
        return red(color) > green(color) * DOMINANCE && blue(color) > green(color) * DOMINANCE;
    }

    private static void fly(ClientGameTestContext context, TestServerContext server, ServerPlayer player, BlockPos min, double[] unit,
                            boolean crosses, String label, List<String> failures) {
        Vec3 eye = context.computeOnClient(client -> {
            int key = NativeClientViewAssertions.portalKey(min);
            return key == 0 ? null : world(WormholesClient.instance().session().portal(key).geometry(), unit);
        });
        if (eye == null) {
            failures.add(label + ": portal geometry is not on the client");
            return;
        }
        Vec3 start = new Vec3(eye.x, eye.y - EYE_HEIGHT, eye.z + RUN_UP);
        teleport(server, player, start.x, start.y, start.z);
        context.waitFor(client -> client.player.position().distanceTo(start) < 0.05D && client.player.getAbilities().flying, STREAM_TIMEOUT_TICKS);
        if (crosses) {
            for (int tick = 0; tick < PREPARE_TIMEOUT_TICKS
                && context.computeOnClient(client -> WormholesClient.instance().preparedTravel().seamless().unprepared()) != null; tick++) {
                context.waitTicks(1);
            }
        }
        String level = context.computeOnClient(client -> client.level.dimension().identifier().toString());
        context.runOnClient(client -> TravelTap.reset());
        double plane = min.getZ() + 0.5D;
        context.getInput().holdKey(options -> options.keyUp);
        int crossing = -1;
        try {
            for (int tick = 0; tick < FLIGHT_TIMEOUT_TICKS && crossing < 0; tick++) {
                context.waitTicks(1);
                crossing = context.computeOnClient(client -> TravelTap.crossingFrame(CROSSING_JUMP));
                if (!crosses && context.computeOnClient(client -> client.player.getZ()) < plane - 2.0D) {
                    break;
                }
            }
        } finally {
            context.getInput().releaseKey(options -> options.keyUp);
        }
        context.waitTicks(SETTLE_TICKS);
        Vec3 end = context.computeOnClient(client -> client.player.position());
        String after = context.computeOnClient(client -> client.level.dimension().identifier().toString());
        BlockPos other = min == A_MIN ? B_MIN : A_MIN;
        double exit = other.getZ() + 0.5D;
        LOGGER.info("[{}] flew from {} to {}, crossing frame {}", label, start, end, crossing);
        if (!level.equals(after)) {
            failures.add(label + ": level changed from " + level + " to " + after);
        }
        if (crosses) {
            if (crossing < 0 || end.z > exit || end.z < exit - WALL_OFFSET || Math.abs(end.x - start.x) > 1.0D) {
                failures.add(label + ": expected arrival beyond the linked portal plane at z " + exit + " but ended at " + end
                    + " with crossing frame " + crossing);
            }
            return;
        }
        if (crossing >= 0 || end.z > plane - 0.5D || end.z < plane - WALL_OFFSET || Math.abs(end.x - start.x) > 1.0D) {
            failures.add(label + ": expected to pass the plane at z " + plane + " without crossing but ended at " + end
                + " with crossing frame " + crossing);
        }
    }

    private static void feather(ClientGameTestContext context, TestServerConnection connection, TestServerContext server, ServerPlayer player,
                                UUID circle, String label) {
        ShapeMesh before = context.computeOnClient(client -> ClientPortalRenderer.instance().apertureShape(NativeClientViewAssertions.portalKey(A_MIN)));
        context.runOnClient(client -> WormholesClient.instance().config().portalEdgeFeather = 0.25D);
        try {
            assertTrue(server.computeOnServer(minecraftServer -> shape(runtime(minecraftServer), player, circle, TURNED_CIRCLE)), "turned circle refused");
            view(context, connection, server, player, A_MIN, TURNED_CIRCLE, LIME);
            String failure = context.computeOnClient(client -> {
                int key = NativeClientViewAssertions.portalKey(A_MIN);
                ShapeMesh after = ClientPortalRenderer.instance().apertureShape(key);
                if (after == null || after == before) {
                    return "the feathered aperture was not rebuilt";
                }
                return ClientPortalRenderer.instance().available(key) ? null : "the feather pass failed the portal frame";
            });
            assertTrue(failure == null, label + ": " + failure);
            context.takeScreenshot("aperture-shape-feather-" + label);
        } finally {
            context.runOnClient(client -> {
                WormholesClient.instance().config().portalEdgeFeather = 0.0D;
                hud(client, false);
            });
        }
    }

    private static void hud(Minecraft client, boolean hidden) {
        if (client.gui.hud.isHidden() != hidden) {
            client.gui.hud.toggle();
        }
    }

    private static Vec3 world(ApertureDescriptor geometry, double[] unit) {
        AperturePolygon aperture = AperturePolygon.from(geometry);
        double[] cell = new double[2];
        aperture.planeShape().placement().pointInto(unit[0], unit[1], cell);
        Vec3d point = aperture.point(cell[0], cell[1]);
        return new Vec3(point.x(), point.y(), point.z());
    }

    private static double[] cell(double column, double row) {
        return new double[] {column / (EDGE / 2.0D) - 1.0D, row / (EDGE / 2.0D) - 1.0D};
    }

    private static List<BlockPos> cells(BlockPos min) {
        List<BlockPos> cells = new ArrayList<>(EDGE * EDGE);
        for (int x = 0; x < EDGE; x++) {
            for (int y = 0; y < EDGE; y++) {
                cells.add(min.offset(x, y, 0));
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

    private static void teleport(TestServerContext server, ServerPlayer player, double x, double y, double z) {
        server.runOnServer(minecraftServer -> {
            player.teleportTo(player.level(), x, y, z, Set.of(), 180.0F, 0.0F, false);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        });
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

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
