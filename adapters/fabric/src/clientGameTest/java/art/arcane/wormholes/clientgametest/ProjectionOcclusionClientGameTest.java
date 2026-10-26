package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.MinecraftDyedItems;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.ClientPortal;
import art.arcane.wormholes.modded.client.ClientRenderAccess;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.cells;
import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;

public final class ProjectionOcclusionClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesProjectionOcclusion");
    private static final int TIMEOUT_TICKS = 600;
    private static final int SETTLE_TICKS = 60;
    private static final int CHECK_FRAMES = 4;
    private static final BlockPos SOURCE_MIN = new BlockPos(40, 70, 20);
    private static final BlockPos DESTINATION_MIN = new BlockPos(200, 70, 20);
    private static final Vec3 CAMERA_POSITION = new Vec3(41.5D, 70.0D, 29.5D);
    private static final List<Vec3> SAMPLES = List.of(new Vec3(40.3D, 72.2D, 20.5D), new Vec3(42.7D, 72.2D, 20.5D));

    @Override
    public void runTest(ClientGameTestContext context) {
        String variant = System.getProperty("wormholes.clientgametest.projectionOcclusion", "all");
        assertTrue(List.of("all", "portal", "mirror").contains(variant), "unknown projection occlusion variant: " + variant);
        for (boolean mirror : List.of(false, true)) {
            String label = mirror ? "mirror" : "portal";
            if (variant.equals("all") || variant.equals(label)) {
                runScenario(context, mirror, label);
            }
        }
    }

    private static void runScenario(ClientGameTestContext context, boolean mirror, String label) {
        ClientViewTestConfig.enableSeamless(false);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            FabricConnection connection = FabricConnection.of(singleplayer);
            connection.waitForChunksDownload();
            context.waitFor(client -> WormholesClient.instance() != null && WormholesClient.instance().session().active(), TIMEOUT_TICKS);
            TestServerContext server = singleplayer.getServer();
            ServerPlayer player = server.computeOnServer(minecraftServer -> connection.getServerPlayer());
            List<UUID> created = new ArrayList<>(2);
            UUID source = server.computeOnServer(minecraftServer -> build(minecraftServer, player, mirror, created));
            ClientOptions options = context.computeOnClient(client -> new ClientOptions(ClientGui.hudHidden(client),
                client.options.getCameraType(), WormholesClient.instance().config().clientRecursion));
            try {
                context.runOnClient(client -> {
                    client.options.setCameraType(CameraType.FIRST_PERSON);
                    WormholesClient.instance().config().clientRecursion = false;
                    if (!ClientGui.hudHidden(client)) {
                        ClientGui.toggleHud(client);
                    }
                });
                server.runOnServer(minecraftServer -> {
                    player.setGameMode(GameType.CREATIVE);
                    player.getAbilities().flying = false;
                    player.onUpdateAbilities();
                    player.teleportTo(player.level(), CAMERA_POSITION.x, CAMERA_POSITION.y, CAMERA_POSITION.z, Set.of(), 180.0F, 0.0F, false);
                    player.setDeltaMovement(Vec3.ZERO);
                });
                context.waitFor(client -> client.player.position().distanceTo(CAMERA_POSITION) < 0.05D, TIMEOUT_TICKS);
                context.getInput().lookAt(180.0F, 0.0F);
                connection.waitForChunksRender();
                context.waitTicks(SETTLE_TICKS);
                List<Crop> crops = context.computeOnClient(client -> SAMPLES.stream().map(position -> {
                    int[] center = FramebufferProbe.screen(client, position);
                    assertTrue(center != null, label + " sample is outside the camera view: " + position);
                    int radius = Math.max(3, ClientRenderAccess.mainTarget(client).width / 320);
                    return new Crop(center[0] - radius, center[1] - radius, radius * 2 + 1);
                }).toList());
                int[][] uncoveredBackground = capture(context, crops);
                projection(context, server, source, ProjectionMode.ON);
                assertRenderPath(context, mirror);
                int[][] projected = capture(context, crops);
                for (int sample = 0; sample < crops.size(); sample++) {
                    assertTrue(difference(projected[sample], uncoveredBackground[sample]) >= 18.0D,
                        "uncovered " + label + " does not visibly project the marker at sample " + sample);
                }
                context.takeScreenshot("projection-occlusion-" + label + "-uncovered");
                for (boolean full : List.of(false, true)) {
                    projection(context, server, source, ProjectionMode.OFF);
                    server.runOnServer(minecraftServer -> foreground(minecraftServer.overworld(), full));
                    connection.waitForChunksRender();
                    context.waitTicks(SETTLE_TICKS);
                    int[][] wall = capture(context, crops);
                    projection(context, server, source, ProjectionMode.ON);
                    String phase = full ? "full" : "partial";
                    context.takeScreenshot("projection-occlusion-" + label + "-" + phase);
                    for (int frame = 0; frame < CHECK_FRAMES; frame++) {
                        assertRenderPath(context, mirror);
                        int[][] actual = capture(context, crops);
                        for (int sample = 0; sample < crops.size(); sample++) {
                            double wallDifference = difference(actual[sample], wall[sample]);
                            if (full || sample == 0) {
                                double contrast = difference(wall[sample], projected[sample]);
                                assertTrue(contrast >= 18.0D, "foreground is indistinguishable from the uncovered projection");
                                double tolerance = Math.max(10.0D, contrast * 0.2D);
                                assertTrue(wallDifference <= tolerance, phase + " foreground failed to occlude " + label + " at sample "
                                    + sample + ", frame " + frame + ": difference=" + wallDifference + ", tolerance=" + tolerance);
                            } else {
                                assertTrue(wallDifference >= 18.0D,
                                    "partially obscured " + label + " lost its exposed projection at frame " + frame);
                            }
                        }
                        context.waitTicks(2);
                    }
                    LOGGER.info("WORMHOLES_PROJECTION_OCCLUSION_PHASE_PASS {} {}", label, phase);
                }
                LOGGER.info("WORMHOLES_PROJECTION_OCCLUSION_PASS {} uncovered, partially obscured, fully obscured", label);
            } finally {
                context.runOnClient(client -> {
                    client.options.setCameraType(options.camera());
                    WormholesClient.instance().config().clientRecursion = options.recursion();
                    if (ClientGui.hudHidden(client) != options.hidden()) {
                        ClientGui.toggleHud(client);
                    }
                });
                server.runOnServer(minecraftServer -> {
                    WormholesModRuntime runtime = runtime(minecraftServer);
                    for (UUID id : created) {
                        if (runtime.portals().get(id) != null) {
                            runtime.portals().remove(player, id);
                        }
                    }
                });
            }
        }
    }

    private static UUID build(MinecraftServer server, ServerPlayer player, boolean mirror, List<UUID> created) {
        ServerLevel level = server.overworld();
        level.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
        level.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
        fill(level, new BlockPos(34, 70, 15), 16, 9, 24, Blocks.AIR.defaultBlockState());
        fill(level, new BlockPos(34, 69, 15), 16, 1, 24, Blocks.SEA_LANTERN.defaultBlockState());
        fill(level, new BlockPos(34, 70, 19), 16, 9, 1, MinecraftDyedItems.concreteBlock(DyeColor.BLUE).defaultBlockState());
        fill(level, new BlockPos(34, 70, 35), 16, 9, 1, MinecraftDyedItems.concreteBlock(DyeColor.YELLOW).defaultBlockState());
        WormholesModRuntime runtime = runtime(server);
        MinecraftPortal source = runtime.portals().create(player.getUUID(), level, cells(SOURCE_MIN), PortalType.PORTAL, new Vec3(0, 0, -1));
        created.add(source.getId());
        source.setMirrorMode(mirror);
        source.setProjectionMode(ProjectionMode.OFF);
        runtime.portals().save(source);
        if (!mirror) {
            fill(level, DESTINATION_MIN.offset(-6, 0, -12), 16, 9, 25, Blocks.AIR.defaultBlockState());
            fill(level, DESTINATION_MIN.offset(-6, -1, -12), 16, 1, 25, Blocks.SEA_LANTERN.defaultBlockState());
            fill(level, DESTINATION_MIN.offset(-6, 0, -10), 16, 9, 1, MinecraftDyedItems.concreteBlock(DyeColor.YELLOW).defaultBlockState());
            fill(level, DESTINATION_MIN.offset(-6, 0, 10), 16, 9, 1, MinecraftDyedItems.concreteBlock(DyeColor.YELLOW).defaultBlockState());
            MinecraftPortal destination = runtime.portals().create(player.getUUID(), level, cells(DESTINATION_MIN), PortalType.PORTAL,
                new Vec3(0, 0, -1));
            created.add(destination.getId());
            assertTrue(runtime.portals().link(player, source.getId(), destination.getId()), "projection portal link rejected");
        }
        return source.getId();
    }

    private static void assertRenderPath(ClientGameTestContext context, boolean mirror) {
        context.runOnClient(client -> {
            NativeClientViewAssertions.assertIsolated();
            int key = NativeClientViewAssertions.portalKey(SOURCE_MIN);
            assertTrue(NativeClientViewAssertions.ready(key), "projection is missing its streamed mesh renderer");
            ClientPortal portal = WormholesClient.instance().session().portal(key);
            assertTrue(portal.geometry().mirror() == mirror, "projection geometry has the wrong mirror mode");
            if (!mirror) {
                assertTrue(!PortalStencilRenderer.instance().claims(portal.geometry()),
                    "linked portal used a resident destination view instead of streamed projection");
            } else {
                assertTrue(PortalStencilRenderer.instance().claims(portal.geometry()),
                    "mirror used streamed projection instead of its native stencil view");
            }
        });
    }

    private static void foreground(ServerLevel level, boolean full) {
        int width = full ? 7 : 3;
        fill(level, new BlockPos(38, 70, 24), width, 5, 1, MinecraftDyedItems.concreteBlock(DyeColor.RED).defaultBlockState());
        fill(level, new BlockPos(38, 70, 25), width, 5, 1, MinecraftDyedItems.concreteBlock(DyeColor.GREEN).defaultBlockState());
    }

    private static void projection(ClientGameTestContext context, TestServerContext server, UUID id, ProjectionMode mode) {
        server.runOnServer(minecraftServer -> {
            WormholesModRuntime runtime = runtime(minecraftServer);
            MinecraftPortal portal = runtime.portals().get(id);
            portal.setProjectionMode(mode);
            runtime.portals().save(portal);
        });
        context.waitFor(client -> mode == ProjectionMode.ON
            ? NativeClientViewAssertions.ready(NativeClientViewAssertions.portalKey(SOURCE_MIN))
            : NativeClientViewAssertions.portalKey(SOURCE_MIN) == 0, TIMEOUT_TICKS);
        context.waitTicks(SETTLE_TICKS);
    }

    private static int[][] capture(ClientGameTestContext context, List<Crop> crops) {
        AtomicReference<FramebufferProbe> captured = new AtomicReference<>();
        context.runOnClient(client -> FramebufferProbe.capture(client, captured::set));
        context.waitFor(client -> captured.get() != null, TIMEOUT_TICKS);
        FramebufferProbe probe = captured.get();
        int[][] result = new int[crops.size()][];
        for (int sample = 0; sample < crops.size(); sample++) {
            Crop crop = crops.get(sample);
            assertTrue(crop.x() >= 0 && crop.y() >= 0 && crop.x() + crop.size() <= probe.width()
                && crop.y() + crop.size() <= probe.height(), "projection crop extends beyond the framebuffer: " + crop);
            int[] pixels = new int[crop.size() * crop.size()];
            for (int y = 0; y < crop.size(); y++) {
                for (int x = 0; x < crop.size(); x++) {
                    pixels[y * crop.size() + x] = probe.read(crop.x() + x, crop.y() + y);
                }
            }
            result[sample] = pixels;
        }
        return result;
    }

    private static double difference(int[] first, int[] second) {
        long distance = 0L;
        for (int index = 0; index < first.length; index++) {
            for (int shift = 0; shift <= 16; shift += 8) {
                distance += Math.abs(((first[index] >> shift) & 255) - ((second[index] >> shift) & 255));
            }
        }
        return distance / (first.length * 3.0D);
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

    private record Crop(int x, int y, int size) {
    }

    private record ClientOptions(boolean hidden, CameraType camera, boolean recursion) {
    }
}
