package art.arcane.wormholes.clientgametest;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.ClientPortal;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.iris.IrisViewPipelines;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11C;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;

public final class ShaderStreamHistoryClientGameTest implements FabricClientGameTest {
    private static final String PACK = "ComplementaryUnbound_r5.8.1.zip";
    private static final int TIMEOUT_TICKS = 600;
    private static final int MIN_FRAMES = 8;
    private static final BlockPos MIRROR = new BlockPos(38, 70, 20);
    private static final BlockPos FIRST = new BlockPos(40, 71, 30);
    private static final BlockPos SECOND = new BlockPos(47, 71, 30);
    private static final BlockPos FIRST_DESTINATION = new BlockPos(120, 71, 30);
    private static final BlockPos SECOND_DESTINATION = new BlockPos(170, 71, 30);
    private static final Vec3 POSITION = new Vec3(45.0D, 71.0D, 38.0D);

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(false);
        List<IrisMeshHistoryTap.Sample> owned = new ArrayList<>();
        try (TestSingleplayerContext world = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
            world.getConnection().waitForChunksDownload();
            context.waitFor(client -> WormholesClient.instance() != null && WormholesClient.instance().session().active(), TIMEOUT_TICKS);
            Options options = context.computeOnClient(client -> new Options(client.options.getCameraType(), client.options.fov().get(),
                client.options.bobView().get(), WormholesClient.instance().config().clientRecursion));
            ServerPlayer player = world.getServer().computeOnServer(server -> world.getConnection().getServerPlayer());
            Scene scene = world.getServer().computeOnServer(server -> build(server, player));
            try {
                context.runOnClient(client -> {
                    assertTrue(NativeTestWindow.hiddenAndUnfocused(client) && NativeTestWindow.mouseReleased(client),
                        "Streamed shader history proof requires a hidden, unfocused GPU client with released mouse");
                    assertTrue(Iris.isPackInUseQuick() && Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline
                        && PACK.equals(Iris.getCurrentPackName()), "Streamed shader history acceptance requires pinned " + PACK);
                    client.options.setCameraType(CameraType.FIRST_PERSON);
                    client.options.fov().set(70);
                    client.options.bobView().set(false);
                    WormholesClient.instance().config().clientRecursion = true;
                });
                world.getServer().runOnServer(server -> place(player, POSITION));
                context.waitFor(client -> client.player != null && client.player.position().distanceTo(POSITION) < 0.05D, TIMEOUT_TICKS);
                context.getInput().lookAt(180.0F, 0.0F);
                world.getConnection().waitForChunksRender();
                context.runOnClient(client -> {
                    IrisHistoryTap.start();
                    IrisMeshHistoryTap.start();
                });
                context.waitFor(client -> failed() || IrisMeshHistoryTap.main() != null && IrisMeshHistoryTap.main().renders() >= MIN_FRAMES,
                    TIMEOUT_TICKS);
                IrisMeshHistoryTap.Sample originalMain = context.computeOnClient(client -> {
                    healthy();
                    return IrisMeshHistoryTap.main();
                });
                int resets = context.computeOnClient(client -> WormholesClient.instance().session().resets());
                projection(world, scene, ProjectionMode.ON);
                await(context, Integer.MIN_VALUE);
                Views original = context.computeOnClient(client -> verify(client, originalMain, resets, "first-open"));
                context.takeScreenshot("shader-stream-history-two-children-native-parent");
                for (double offset : new double[]{-0.75D, 0.75D, 0.0D}) {
                    Views before = context.computeOnClient(client -> verify(client, originalMain, resets, "before-motion"));
                    Vec3 position = POSITION.add(offset, 0.0D, 0.0D);
                    world.getServer().runOnServer(server -> place(player, position));
                    context.waitFor(client -> client.player.position().distanceTo(position) < 0.05D, TIMEOUT_TICKS);
                    context.getInput().lookAt(offset < 0.0D ? 175.0F : offset > 0.0D ? 185.0F : 180.0F, 0.0F);
                    await(context, before.main().frame());
                    context.waitTicks(20);
                    Views moved = context.computeOnClient(client -> verify(client, originalMain, resets, "motion-" + offset));
                    stable(original, moved, "camera motion");
                    assertTrue(moved.first().camera().distanceTo(before.first().camera()) > 0.1D
                        && moved.second().camera().distanceTo(before.second().camera()) > 0.1D,
                        "Both actual streamed shader camera uniforms must move with the physical observer");
                }
                long closed = System.nanoTime();
                projection(world, scene, ProjectionMode.OFF);
                context.waitFor(client -> !announced(MIRROR) && !announced(FIRST) && !announced(SECOND), TIMEOUT_TICKS);
                context.waitTicks(20);
                context.runOnClient(client -> {
                    healthy();
                    for (IrisMeshHistoryTap.Sample sample : List.of(original.parent(), original.first(), original.second())) {
                        live(sample);
                    }
                });
                int previousFrame = context.computeOnClient(client -> IrisMeshHistoryTap.main().frame());
                projection(world, scene, ProjectionMode.ON);
                await(context, previousFrame);
                Views reopened = context.computeOnClient(client -> verify(client, originalMain, resets, "brief-reopen"));
                assertTrue(System.nanoTime() - closed < 55_000_000_000L, "Brief close/reopen exceeded the retained-history window");
                stable(original, reopened, "brief close/reopen");
                context.takeScreenshot("shader-stream-history-reopened");
                owned.addAll(context.computeOnClient(client -> IrisMeshHistoryTap.owned()));
                assertTrue(owned.size() >= 3, "No owned GPU histories were retained for world-close proof");
            } finally {
                context.runOnClient(client -> {
                    IrisMeshHistoryTap.stop();
                    IrisHistoryTap.stop();
                    client.options.setCameraType(options.camera());
                    client.options.fov().set(options.fov());
                    client.options.bobView().set(options.bobbing());
                    WormholesClient.instance().config().clientRecursion = options.recursion();
                });
                world.getServer().runOnServer(server -> {
                    for (UUID id : scene.portals()) {
                        runtime(server).portals().remove(player, id);
                    }
                });
            }
        }
        context.runOnClient(client -> {
            assertTrue(IrisViewPipelines.diagnostics().cached() == 0 && IrisViewPipelines.diagnostics().pending() == 0,
                "World close retained streamed/native camera pipelines or pending preparation");
            for (IrisMeshHistoryTap.Sample sample : owned) {
                assertTrue(!GL11C.glIsTexture(sample.mainTexture()) && !GL11C.glIsTexture(sample.altTexture()),
                    "World close retained an owned camera's actual colortex5 GPU textures");
            }
            persist(client, "world-close", owned);
            System.out.println("WORMHOLES_IRIS_STREAM_HISTORY_PASS owned=" + owned.size()
                + " textures-deleted=true pipeline-cache=0 proof=GPU-history-isolation image-correctness=unverified");
        });
    }

    private static Views verify(Minecraft client, IrisMeshHistoryTap.Sample originalMain, int resets, String phase) {
        healthy();
        assertTrue(WormholesClient.instance().session().active() && WormholesClient.instance().session().resets() == resets
            && WormholesClient.instance().session().protocolFailures() == 0L, "Streamed acceptance session reset or failed its protocol");
        IrisMeshHistoryTap.Sample main = IrisMeshHistoryTap.main();
        IrisMeshHistoryTap.Sample parent = IrisMeshHistoryTap.nativeView(MIRROR);
        IrisMeshHistoryTap.Sample first = IrisMeshHistoryTap.child(MIRROR, FIRST);
        IrisMeshHistoryTap.Sample second = IrisMeshHistoryTap.child(MIRROR, SECOND);
        assertTrue(main != null && parent != null && first != null && second != null,
            "Main, native mirror parent and two different streamed children must all finalize");
        assertTrue(main.frame() == parent.frame() && main.frame() == first.frame() && main.frame() == second.frame(),
            "Both streamed children, their native mirror parent and the main view must render in the same frame");
        same(originalMain, main, "main camera");
        assertTrue(first.parent() == parent.pipeline() && second.parent() == parent.pipeline()
            && first.parentDepth() == 1 && second.parentDepth() == 1, "Both actual streamed draws must be enclosed by the native mirror pipeline");
        assertTrue(first.draws() > 0 && second.draws() > 0 && !first.identity().equals(second.identity())
            && first.camera().distanceTo(second.camera()) > 2.0D, "Two real streamed mesh draws must use distinct physical cameras");
        for (IrisMeshHistoryTap.Sample child : List.of(first, second)) {
            assertTrue(child.identity().traversal().size() == 2
                && child.identity().traversal().getFirst().mirror()
                && child.identity().source().equals(Level.OVERWORLD.identifier().toString())
                && child.identity().destination().equals(Level.NETHER.identifier().toString()),
                "The actual streamed camera must follow its native Overworld mirror into an unavailable Nether destination");
        }
        assertTrue(WormholesClient.instance().seamlessTravel().arms().isEmpty(),
            "Streamed fallback scene unexpectedly negotiated a native seamless destination");
        assertTrue(IrisMeshHistoryTap.restored() >= first.renders() + second.renders(), "Actual streamed scope exits did not restore their parent");
        List<IrisMeshHistoryTap.Sample> samples = List.of(main, parent, first, second);
        for (int index = 0; index < samples.size(); index++) {
            IrisMeshHistoryTap.Sample sample = samples.get(index);
            live(sample);
            for (int other = index + 1; other < samples.size(); other++) {
                isolated(sample, samples.get(other));
            }
        }
        healthy();
        persist(client, phase, samples);
        System.out.println("WORMHOLES_IRIS_STREAM_HISTORY_PHASE " + phase + " frame=" + main.frame()
            + " parent-restorations=" + IrisMeshHistoryTap.restored());
        return new Views(main, parent, first, second);
    }

    private static void await(ClientGameTestContext context, int afterFrame) {
        try {
            context.waitFor(client -> failed() || simultaneous(afterFrame), TIMEOUT_TICKS);
        } catch (AssertionError failure) {
            context.runOnClient(client -> {
                List<IrisMeshHistoryTap.Sample> samples = new ArrayList<>(IrisMeshHistoryTap.owned());
                if (IrisMeshHistoryTap.main() != null) {
                    samples.add(IrisMeshHistoryTap.main());
                }
                persist(client, "await-failure", samples);
                System.out.println("WORMHOLES_IRIS_STREAM_HISTORY_WAIT_FAILURE cameras=" + samples.size()
                    + " parent-restorations=" + IrisMeshHistoryTap.restored());
            });
            context.takeScreenshot("shader-stream-history-await-failure");
            throw failure;
        }
        context.runOnClient(client -> healthy());
    }

    private static boolean simultaneous(int afterFrame) {
        IrisMeshHistoryTap.Sample main = IrisMeshHistoryTap.main();
        IrisMeshHistoryTap.Sample parent = IrisMeshHistoryTap.nativeView(MIRROR);
        IrisMeshHistoryTap.Sample first = IrisMeshHistoryTap.child(MIRROR, FIRST);
        IrisMeshHistoryTap.Sample second = IrisMeshHistoryTap.child(MIRROR, SECOND);
        return enough(main, afterFrame) && enough(parent, afterFrame) && enough(first, afterFrame) && enough(second, afterFrame)
            && main.frame() == parent.frame() && main.frame() == first.frame() && main.frame() == second.frame();
    }

    private static boolean enough(IrisMeshHistoryTap.Sample sample, int afterFrame) {
        return sample != null && sample.renders() >= MIN_FRAMES && sample.frame() > afterFrame;
    }

    private static boolean failed() {
        return IrisHistoryTap.failure() != null || IrisMeshHistoryTap.failure() != null;
    }

    private static void healthy() {
        assertTrue(IrisHistoryTap.failure() == null, IrisHistoryTap.failure());
        assertTrue(IrisMeshHistoryTap.failure() == null, IrisMeshHistoryTap.failure());
    }

    private static void live(IrisMeshHistoryTap.Sample sample) {
        assertTrue(sample.mainTexture() != sample.altTexture() && GL11C.glIsTexture(sample.mainTexture())
            && GL11C.glIsTexture(sample.altTexture()), "A sampled temporal texture pair is no longer distinct and GPU-live");
    }

    private static void isolated(IrisMeshHistoryTap.Sample first, IrisMeshHistoryTap.Sample second) {
        assertTrue(first.pipeline() != second.pipeline(), "Actual full Iris camera pipelines are shared");
        assertTrue(first.mainTexture() != second.mainTexture() && first.mainTexture() != second.altTexture()
            && first.altTexture() != second.mainTexture() && first.altTexture() != second.altTexture(),
            "Actual GPU colortex5 main/alt textures overlap between cameras");
    }

    private static void stable(Views previous, Views next, String phase) {
        same(previous.parent(), next.parent(), phase + " native parent");
        same(previous.first(), next.first(), phase + " first streamed child");
        same(previous.second(), next.second(), phase + " second streamed child");
    }

    private static void same(IrisMeshHistoryTap.Sample previous, IrisMeshHistoryTap.Sample next, String label) {
        assertTrue(previous.pipeline() == next.pipeline() && previous.mainTexture() == next.mainTexture()
            && previous.altTexture() == next.altTexture(), label + " replaced its full pipeline or actual GPU history pair");
    }

    private static boolean announced(BlockPos origin) {
        for (ClientPortal portal : WormholesClient.instance().session().portals().values()) {
            ApertureDescriptor geometry = portal.geometry();
            if (geometry.originX() == origin.getX() && geometry.originY() == origin.getY() && geometry.originZ() == origin.getZ()) {
                return true;
            }
        }
        return false;
    }

    private static void projection(TestSingleplayerContext world, Scene scene, ProjectionMode mode) {
        world.getServer().runOnServer(server -> {
            WormholesModRuntime runtime = runtime(server);
            for (UUID id : scene.sources()) {
                MinecraftPortal portal = runtime.portals().get(id);
                portal.setProjectionMode(mode);
                runtime.portals().save(portal);
            }
        });
    }

    private static Scene build(MinecraftServer server, ServerPlayer player) {
        ServerLevel source = server.overworld();
        ServerLevel destination = Objects.requireNonNull(server.getLevel(Level.NETHER));
        source.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
        source.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
        fill(source, new BlockPos(32, 69, 12), 28, 14, 34, Blocks.AIR.defaultBlockState());
        fill(source, new BlockPos(32, 69, 12), 28, 1, 34, Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState());
        for (BlockPos origin : List.of(FIRST_DESTINATION, SECOND_DESTINATION)) {
            fill(destination, origin.offset(-8, -2, -12), 20, 12, 28, Blocks.AIR.defaultBlockState());
            fill(destination, origin.offset(-8, -2, -12), 20, 1, 28, Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState());
            fill(destination, origin.offset(-4, 0, -5), 12, 7, 1,
                Blocks.CONCRETE.pick(origin.equals(FIRST_DESTINATION) ? DyeColor.YELLOW : DyeColor.CYAN).defaultBlockState());
            fill(destination, origin.offset(-4, 6, -4), 12, 1, 1, Blocks.SEA_LANTERN.defaultBlockState());
        }
        WormholesModRuntime runtime = runtime(server);
        assertTrue(!runtime.configuration().settings().getClientView().seamlessTravel,
            "Streamed shader fixture must disable server seamless travel before its world starts");
        List<UUID> portals = new ArrayList<>(5);
        MinecraftPortal mirror = portal(runtime, player, source, MIRROR, 14, 8, new Vec3(0, 0, -1), portals);
        mirror.setMirrorMode(true);
        runtime.portals().save(mirror);
        MinecraftPortal first = portal(runtime, player, source, FIRST, 3, 4, new Vec3(0, 0, 1), portals);
        MinecraftPortal second = portal(runtime, player, source, SECOND, 3, 4, new Vec3(0, 0, 1), portals);
        MinecraftPortal firstDestination = portal(runtime, player, destination, FIRST_DESTINATION, 3, 4, new Vec3(0, 0, -1), portals);
        MinecraftPortal secondDestination = portal(runtime, player, destination, SECOND_DESTINATION, 3, 4, new Vec3(0, 0, -1), portals);
        assertTrue(runtime.portals().link(player, first.getId(), firstDestination.getId())
            && runtime.portals().link(player, second.getId(), secondDestination.getId()), "Streamed child destination linking failed");
        return new Scene(List.copyOf(portals), List.of(mirror.getId(), first.getId(), second.getId()));
    }

    private static MinecraftPortal portal(WormholesModRuntime runtime, ServerPlayer player, ServerLevel level, BlockPos origin,
                                          int width, int height, Vec3 look, List<UUID> portals) {
        List<BlockPos> cells = new ArrayList<>(width * height);
        for (int x = -1; x <= width; x++) {
            for (int y = -1; y <= height; y++) {
                BlockPos cell = origin.offset(x, y, 0);
                boolean border = x == -1 || x == width || y == -1 || y == height;
                level.setBlockAndUpdate(cell, border ? Blocks.POLISHED_DEEPSLATE.defaultBlockState() : Blocks.AIR.defaultBlockState());
                if (!border) {
                    cells.add(cell);
                }
            }
        }
        MinecraftPortal portal = Objects.requireNonNull(runtime.portals().create(player.getUUID(), level, cells, PortalType.PORTAL, look));
        portal.setAmbientStyle(AmbientParticleStyle.OFF);
        portal.setProjectionMode(ProjectionMode.OFF);
        portal.setNetworkViewDepth(64);
        portal.setBlackoutBackground(false);
        runtime.portals().save(portal);
        portals.add(portal.getId());
        return portal;
    }

    private static void place(ServerPlayer player, Vec3 position) {
        player.setGameMode(GameType.CREATIVE);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.teleportTo(player.level(), position.x, position.y, position.z, Set.of(), 180.0F, 0.0F, false);
        player.setDeltaMovement(Vec3.ZERO);
    }

    private static void fill(ServerLevel level, BlockPos origin, int width, int height, int depth, BlockState state) {
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < depth; z++) {
                    level.setBlockAndUpdate(origin.offset(x, y, z), state);
                }
            }
        }
    }

    private static void persist(Minecraft client, String phase, List<IrisMeshHistoryTap.Sample> samples) {
        List<CameraEvidence> cameras = new ArrayList<>(samples.size());
        for (IrisMeshHistoryTap.Sample sample : samples) {
            cameras.add(new CameraEvidence(System.identityHashCode(sample.pipeline()), sample.mainTexture(), sample.altTexture(),
                GL11C.glIsTexture(sample.mainTexture()), GL11C.glIsTexture(sample.altTexture()), sample.camera().toString(),
                sample.matrix().toString(), sample.frame(), sample.renders(), sample.draws(), String.valueOf(sample.identity()),
                String.valueOf(sample.parentOrigin()), sample.parentDepth(), System.identityHashCode(sample.parent())));
        }
        Path path = client.gameDirectory.toPath().resolve("screenshots/shader-stream-history/" + phase + ".json");
        List<StreamEvidence> streams = new ArrayList<>();
        for (ClientPortal portal : WormholesClient.instance().session().portals().values()) {
            streams.add(new StreamEvidence(portal.portalKey(), portal.geometry().toString(),
                String.valueOf(WormholesClient.instance().session().environment(portal.portalKey()))));
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(new Evidence(phase, PACK,
                "actual GPU history isolation; whole composed shader image correctness unverified", IrisMeshHistoryTap.restored(), cameras,
                WormholesClient.instance().session().caps(), List.copyOf(streams), IrisViewPipelines.cameraSnapshots())));
        } catch (IOException failure) {
            throw new AssertionError("Could not retain streamed shader GPU history evidence", failure);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Scene(List<UUID> portals, List<UUID> sources) {
    }

    private record Options(CameraType camera, int fov, boolean bobbing, boolean recursion) {
    }

    private record Views(IrisMeshHistoryTap.Sample main, IrisMeshHistoryTap.Sample parent,
                         IrisMeshHistoryTap.Sample first, IrisMeshHistoryTap.Sample second) {
    }

    private record CameraEvidence(int pipeline, int mainTexture, int altTexture, boolean mainLive, boolean altLive,
                                  String camera, String modelview, int frame, int renders, int draws, String traversal,
                                  String parentOrigin, int parentDepth, int parentPipeline) {
    }

    private record StreamEvidence(int key, String geometry, String environment) {
    }

    private record Evidence(String phase, String pack, String proof, int parentRestorations, List<CameraEvidence> cameras,
                            long capabilities, List<StreamEvidence> streams, IrisViewPipelines.CameraSnapshots cameraPaths) {
    }
}
