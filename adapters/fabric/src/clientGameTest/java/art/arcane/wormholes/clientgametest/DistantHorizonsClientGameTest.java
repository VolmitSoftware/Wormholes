package art.arcane.wormholes.clientgametest;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.dh.DhClipPrograms;
import art.arcane.wormholes.modded.client.render.iris.IrisClipPlanes;
import art.arcane.wormholes.modded.client.render.stencil.PortalBackends;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.modded.mixin.client.DhPortalClientApiAccess;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.transit.OrientationPolicy;
import com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper;
import com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper;
import com.seibel.distanthorizons.common.wrappers.world.ServerLevelWrapper;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.RenderBufferHandler;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4d;
import org.lwjgl.opengl.GL11C;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.cells;
import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;
import static art.arcane.wormholes.clientgametest.SeamlessScenario.assertTrue;

public final class DistantHorizonsClientGameTest implements FabricClientGameTest {
    private static final int LOD_TIMEOUT_TICKS = 2400;
    private static final int LANDMARK_TASK_TIMEOUT_SECONDS = 120;
    private static final int TRAVEL_TIMEOUT_TICKS = 600;
    private static final int SETTLE_TICKS = 100;
    private static final BlockPos SOURCE = new BlockPos(0, 70, 20);
    private static final BlockPos DESTINATION = new BlockPos(0, 70, 320);
    private static final BlockPos FIRST_MIRROR = new BlockPos(40, 70, 20);
    private static final BlockPos SECOND_MIRROR = new BlockPos(46, 70, 23);
    private static final BlockPos NESTED_MIRROR = new BlockPos(40, 70, 44);
    private static final Vec3 MIRROR_CAMERA = new Vec3(41.5D, 70.0D, 30.5D);

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(true);
        boolean shaders = Boolean.getBoolean("wormholes.test.dhShaders");
        context.runOnClient(client -> {
            verifyClipScopes();
            DistantHorizonsTap.start(shaders);
        });
        DistantHorizonsTap.History[] closingHistories = new DistantHorizonsTap.History[2];
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            SeamlessClient client = new FabricSeamlessClient(context, world.getConnection());
            SeamlessScenario.join(client);
            SeamlessScenario.assertSeamlessNegotiated(client);
            context.runOnClient(minecraft -> ClientMeshLightingAssertions.verify(minecraft.level));
            System.out.println("WORMHOLES_CLIENT_LIGHTING_SCOPE_PASS");
            Options options = context.computeOnClient(minecraft -> new Options(minecraft.options.getCameraType(),
                minecraft.gui.hud.isHidden(), minecraft.options.renderDistance().get(),
                WormholesClient.instance().config().clientRecursion, Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get()));
            TestServerContext server = world.getServer();
            ServerPlayer player = server.computeOnServer(minecraftServer -> world.getConnection().getServerPlayer());
            List<UUID> created = new ArrayList<>();
            try {
                context.runOnClient(minecraft -> {
                    assertHidden(minecraft);
                    assertTrue(PortalBackends.pipeline().deferred() == shaders, "DH shader mode differs from the requested run");
                    minecraft.options.setCameraType(CameraType.FIRST_PERSON);
                    minecraft.options.renderDistance().set(4);
                    WormholesClient.instance().config().clientRecursion = true;
                    Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.set(64);
                    if (!minecraft.gui.hud.isHidden()) {
                        minecraft.gui.hud.toggle();
                    }
                });
                FabricSeamlessServer travelServer = new FabricSeamlessServer(server, world.getConnection());
                server.runOnServer(minecraftServer -> prepareWorlds(minecraftServer, player));
                seed(context, world, player, Level.OVERWORLD, SOURCE);
                seed(context, world, player, Level.END, DESTINATION);
                context.waitTicks(SETTLE_TICKS);
                if (shaders && !Boolean.getBoolean("wormholes.clientgametest.dhAnimatedComparison")) {
                    context.runOnClient(minecraft -> DhShaderComparisonClock.capture());
                    context.waitTicks(20);
                }
                if (shaders && Boolean.getBoolean("wormholes.clientgametest.dhImageDiagnostic")) {
                    context.runOnClient(minecraft -> DistantHorizonsTap.dumpAnimatedUniforms("dh-destination-main-control"));
                }
                BufferedImage destinationControl = DhComposedImageAssertions.read(
                    context.takeScreenshot("dh-destination-main-landmark-control"));
                if (shaders) {
                    context.runOnClient(minecraft -> DhShaderComparisonClock.verify(false));
                }
                DhComposedImageAssertions.LandmarkContrast shaderLandmark = shaders
                    ? DhComposedImageAssertions.shaderDestinationControl(destinationControl) : null;
                if (!shaders) {
                    DhComposedImageAssertions.assertDestinationLandmark(destinationControl);
                }
                teleport(context, server, player, Level.OVERWORLD, new Vec3(1.5D, 70.0D, 26.5D), 180.0F);
                world.getConnection().waitForChunksDownload();
                world.getConnection().waitForChunksRender();
                context.waitTicks(SETTLE_TICKS);
                long referenceSamples = context.computeOnClient(minecraft -> DistantHorizonsTap.visibleSamples(SOURCE, 1));
                context.waitTicks(20);
                context.runOnClient(minecraft -> assertTrue(DistantHorizonsTap.visibleSamples(SOURCE, 1) == referenceSamples,
                    "DH source-floor reference still contains destination LOD draws"));
                BufferedImage sourceFloor = sourceReference(context, shaders);
                int acceptedResets = context.computeOnClient(minecraft -> WormholesClient.instance().session().resets());
                SeamlessScenario.Route route = server.computeOnServer(minecraftServer -> buildRoute(minecraftServer, player, created));
                travelServer.approach(route);
                context.waitFor(minecraft -> minecraft.level.dimension().equals(Level.OVERWORLD)
                    && minecraft.player.position().distanceTo(new Vec3(1.5D, 70.0D, 26.5D)) < 0.1D, TRAVEL_TIMEOUT_TICKS);
                context.getInput().lookAt(180.0F, 0.0F);
                SeamlessScenario.awaitPrepared(client);
                context.takeScreenshot("dh-source-approach");
                awaitLod(context, SOURCE, 1, "cross-dimension-destination", referenceSamples);
                if (shaders) {
                    context.runOnClient(minecraft -> DhShaderComparisonClock.verify(true));
                }
                if (shaders && Boolean.getBoolean("wormholes.clientgametest.dhImageDiagnostic")) {
                    context.runOnClient(minecraft -> DistantHorizonsTap.dumpAnimatedUniforms("dh-initial-portal"));
                }
                BufferedImage destinationView = DhComposedImageAssertions.read(context.takeScreenshot("dh-cross-dimension-distant-terrain"));
                DhComposedImageAssertions.assertSourceFloor(sourceFloor, destinationView);
                if (shaders) {
                    try {
                        DhComposedImageAssertions.assertShaderDestinationLandmark(shaderLandmark, destinationView);
                    } catch (AssertionError failure) {
                        shaderImageDiagnostic(context);
                        throw failure;
                    }
                } else {
                    DhComposedImageAssertions.assertSourceBackground(sourceFloor, destinationView);
                    DhComposedImageAssertions.assertDestinationLandmark(destinationView);
                }
                if (shaders) {
                    float resumed = context.computeOnClient(minecraft -> DhShaderComparisonClock.release());
                    context.waitFor(minecraft -> DhShaderComparisonClock.advancing(resumed), 40);
                    System.out.println("WORMHOLES_DH_SHADER_CLOCK_RESUMED_PASS previous=" + resumed);
                }
                cameraAcrossBeforePlayer(context, server, player);
                SeamlessScenario.awaitPrepared(client);
                for (int trip = 1; trip <= 2; trip++) {
                    long outboundPipelines = shaders ? context.computeOnClient(minecraft -> IrisPipelineCostTap.snapshot().creations()) : 0L;
                    SeamlessScenario.Crossing outbound = SeamlessScenario.walkThrough(client, "dh-outbound-" + trip);
                    SeamlessScenario.assertSeamlessTravel(client, route, outbound, SeamlessScenario.LOADED_RETURN_VIEW_TICKS);
                    assertPreparedCrossing(context, shaders, outboundPipelines, "dh-outbound-" + trip);
                    awaitMain(context, "arrival-main-" + trip);
                    SeamlessScenario.turnBack(client, route);
                    long previous = context.computeOnClient(minecraft -> DistantHorizonsTap.visibleSamples(DESTINATION, 1));
                    awaitLod(context, DESTINATION, 1, "return-distant-terrain-" + trip, previous);
                    long inboundPipelines = shaders ? context.computeOnClient(minecraft -> IrisPipelineCostTap.snapshot().creations()) : 0L;
                    SeamlessScenario.Crossing inbound = SeamlessScenario.walkThrough(client, "dh-return-" + trip);
                    SeamlessScenario.assertSeamlessReturn(client, route, inbound, SeamlessScenario.LOADED_RETURN_VIEW_TICKS);
                    assertPreparedCrossing(context, shaders, inboundPipelines, "dh-return-" + trip);
                    awaitMain(context, "return-main-" + trip);
                    if (trip < 2) {
                        SeamlessScenario.turnAround(client, route.outbound().exit().sourceOrigin());
                        long before = context.computeOnClient(minecraft -> DistantHorizonsTap.visibleSamples(SOURCE, 1));
                        awaitLod(context, SOURCE, 1, "cross-dimension-revisit", before);
                    }
                }
                travelServer.remove(route);
                UUID[] mirrors = server.computeOnServer(minecraftServer -> mirrors(minecraftServer, player, created));
                teleport(context, server, player, Level.OVERWORLD, MIRROR_CAMERA, 180.0F);
                awaitLod(context, FIRST_MIRROR, 1, "first-mirror", 0L);
                awaitLod(context, SECOND_MIRROR, 1, "second-mirror", 0L);
                awaitNested(context);
                verifyTerrainBuffers(context);
                context.takeScreenshot("dh-mirrors-and-recursion");
                if (context.computeOnClient(minecraft -> DistantHorizonsTap.temporalHistorySupported())) {
                    verifyHistory(context);
                }
                revisit(context, server, player, mirrors, shaders);
                expire(context, server, player, mirrors);
                context.waitFor(minecraft -> WormholesClient.instance().outboundPending() == 0, 200);
                context.runOnClient(minecraft -> {
                    WormholesClient owner = WormholesClient.instance();
                    assertTrue(owner.session().active(), "DH acceptance lost the active ClientView session");
                    assertTrue(owner.session().resets() == acceptedResets, "DH acceptance reset the ClientView session");
                    assertTrue(owner.session().protocolFailures() == 0L && owner.tickState().protocolFailures() == 0L,
                        "DH acceptance encountered a ClientView protocol failure");
                    assertTrue(owner.tickState().sendFailures() == 0L && owner.receiver().decodeFailures() == 0L,
                        "DH acceptance encountered a ClientView transport failure");
                    System.out.println("WORMHOLES_DH_SESSION_PASS active=true resets-stable=true protocol-failures=0");
                });
                context.runOnClient(minecraft -> {
                    assertTrue(DistantHorizonsTap.failure() == null, DistantHorizonsTap.failure());
                    DistantHorizonsTap.checkpoint("complete");
                });
                context.runOnClient(minecraft -> {
                    closingHistories[0] = DistantHorizonsTap.history(FIRST_MIRROR, 1);
                    closingHistories[1] = DistantHorizonsTap.history(SECOND_MIRROR, 1);
                });
                System.out.println("WORMHOLES_DH_ACCEPTANCE_PASS shaders=" + shaders);
            } catch (RuntimeException | Error failure) {
                failure.printStackTrace();
                System.err.println("WORMHOLES_DH_ACCEPTANCE_FAIL shaders=" + shaders);
                context.runOnClient(DistantHorizonsClientGameTest::failureViews);
                throw failure;
            } finally {
                context.runOnClient(minecraft -> {
                    DistantHorizonsTap.checkpoint("cleanup");
                    DistantHorizonsTap.writeEvidence();
                    DistantHorizonsTap.stop();
                    minecraft.options.setCameraType(options.camera());
                    minecraft.options.renderDistance().set(options.renderDistance());
                    Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.set(options.lodDistance());
                    WormholesClient.instance().config().clientRecursion = options.recursion();
                    if (minecraft.gui.hud.isHidden() != options.hidden()) {
                        minecraft.gui.hud.toggle();
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
        } finally {
            context.runOnClient(minecraft -> {
                if (shaders) {
                    DhShaderComparisonClock.release();
                }
                DistantHorizonsTap.stop();
                for (DistantHorizonsTap.History history : closingHistories) {
                    if (history != null) {
                        released(history, "world close");
                    }
                }
                if (closingHistories[0] != null) {
                    System.out.println("WORMHOLES_DH_WORLD_CLOSE_PASS actual-history-textures-released=true");
                }
            });
        }
    }

    private static void verifyClipScopes() {
        IrisClipPlanes.State original = IrisClipPlanes.capture();
        try {
            for (int mask : new int[] {0, 0xFF, 0xA5}) {
                IrisClipPlanes.State parent = new IrisClipPlanes.State(mask, -1);
                parent.restore();
                try (DhClipPrograms.Scope outer = DhClipPrograms.open(new Vector4d(0.0D, 0.0D, 1.0D, -20.0D), new Vec3d(0.0D, 0.0D, 0.0D))) {
                    IrisClipPlanes.State nested = new IrisClipPlanes.State(0x5A, 3);
                    nested.restore();
                    try (DhClipPrograms.Scope inner = DhClipPrograms.open(new Vector4d(1.0D, 0.0D, 0.0D, -40.0D), new Vec3d(0.0D, 0.0D, 0.0D))) {
                        new IrisClipPlanes.State(0x81, 7).restore();
                        IrisClipPlanes.release();
                        assertTrue(IrisClipPlanes.capture().equals(new IrisClipPlanes.State(1, -1)), "Iris release changed an unrelated clip distance");
                    }
                    assertTrue(IrisClipPlanes.capture().equals(nested), "Nested DH clipping did not restore the real GL mask and Iris tracker");
                    IrisClipPlanes.release();
                    assertTrue(IrisClipPlanes.capture().equals(new IrisClipPlanes.State(0x52, -1)), "Restored Iris tracker did not release its clip distance");
                }
                assertTrue(IrisClipPlanes.capture().equals(parent), "DH clipping did not restore all eight real GL clip distances");
            }
            IllegalStateException expected = new IllegalStateException("clip-scope-exit");
            try (DhClipPrograms.Scope scope = DhClipPrograms.open(new Vector4d(0.0D, 1.0D, 0.0D, -70.0D), new Vec3d(0.0D, 0.0D, 0.0D))) {
                new IrisClipPlanes.State(0xFF, 5).restore();
                throw expected;
            } catch (IllegalStateException failure) {
                assertTrue(failure == expected, "DH clip scope failed while restoring an exceptional exit");
            }
            assertTrue(IrisClipPlanes.capture().equals(new IrisClipPlanes.State(0xA5, -1)), "Exceptional DH scope did not restore its GL mask");
            System.out.println("WORMHOLES_DH_CLIP_SCOPE_PASS all-eight-bits=true nested=true tracker=true exceptional=true");
        } finally {
            original.restore();
        }
    }

    private static void cameraAcrossBeforePlayer(ClientGameTestContext context, TestServerContext server, ServerPlayer player) {
        long before = context.computeOnClient(minecraft -> DistantHorizonsTap.virtualMainDraws());
        teleport(context, server, player, Level.OVERWORLD, new Vec3(1.5D, 70.0D, 22.5D), 180.0F);
        context.runOnClient(minecraft -> minecraft.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
        context.waitFor(minecraft -> DistantHorizonsTap.failure() != null || DistantHorizonsTap.virtualMainDraws() > before, LOD_TIMEOUT_TICKS);
        context.runOnClient(minecraft -> {
            assertTrue(DistantHorizonsTap.failure() == null, DistantHorizonsTap.failure());
            assertTrue(minecraft.player.level().dimension().equals(Level.OVERWORLD)
                && minecraft.level.dimension().equals(Level.OVERWORLD) && !WormholesClient.instance().seamlessTravel().pending(),
                "Third-person DH camera crossing moved the physical player or failed to restore its main world");
            assertTrue(Level.END.identifier().toString().equals(DistantHorizonsTap.virtualMainWorld()),
                "The third-person DH main camera did not draw the destination world before the player crossed");
            DistantHorizonsTap.checkpoint("camera-across-before-player");
        });
        context.takeScreenshot("dh-camera-across-before-player");
        context.runOnClient(minecraft -> minecraft.options.setCameraType(CameraType.FIRST_PERSON));
        teleport(context, server, player, Level.OVERWORLD, new Vec3(1.5D, 70.0D, 26.5D), 180.0F);
    }

    private static void failureViews(Minecraft minecraft) {
        System.err.println("DH failure source view: " + NativeClientViewAssertions.describe(SOURCE, List.of(SOURCE)));
        System.err.println("DH failure destination view: " + NativeClientViewAssertions.describe(DESTINATION, List.of(DESTINATION)));
        for (PortalView view : WormholesClient.instance().portalViews().current()) {
            System.err.println("DH failure native view: " + view.kind() + " " + view.source().dimension().identifier()
                + " -> " + view.destination().dimension().identifier() + " plane=" + view.surface().planePoint()
                + " normal=" + view.surface().servedNormal());
        }
    }

    private static void assertPreparedCrossing(ClientGameTestContext context, boolean shaders, long before, String label) {
        if (!shaders) {
            return;
        }
        context.runOnClient(minecraft -> {
            assertTrue(IrisPipelineCostTap.snapshot().creations() == before,
                label + ": traversal constructed an unprepared Iris camera pipeline");
            DistantHorizonsTap.checkpoint(label + "-prepared");
        });
    }

    private static SeamlessScenario.Route buildRoute(MinecraftServer server, ServerPlayer player, List<UUID> created) {
        SeamlessScenario.Route route = SeamlessScenario.build(player, server,
            new SeamlessScenario.RouteSpec(Level.OVERWORLD, SOURCE, Level.END, DESTINATION, OrientationPolicy.FRAME, false));
        created.add(route.source());
        created.add(route.destination());
        prepareWorlds(server, player);
        return route;
    }

    private static void prepareWorlds(MinecraftServer server, ServerPlayer player) {
        player.setGameMode(GameType.CREATIVE);
        player.getAbilities().flying = false;
        player.onUpdateAbilities();
        prepare(server, server.overworld(), SOURCE, DyeColor.MAGENTA);
        prepare(server, Objects.requireNonNull(server.getLevel(Level.END)), DESTINATION, DyeColor.CYAN);
        Objects.requireNonNull(server.getLevel(Level.END)).setBlockAndUpdate(DESTINATION.offset(-4, 1, -3), Blocks.STONE.defaultBlockState());
    }

    private static void prepare(MinecraftServer server, ServerLevel level, BlockPos center, DyeColor color) {
        level.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
        level.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
        fill(level, center.offset(-16, 0, -16), 80, 12, 48, Blocks.AIR.defaultBlockState());
        fill(level, center.offset(-16, -1, -16), 80, 1, 48, Blocks.SEA_LANTERN.defaultBlockState());
        for (int side : new int[]{-1, 1}) {
            BlockPos landmark = center.offset(-24, -1, side * 256);
            fill(level, landmark.offset(0, 0, -24), 96, 1, 48, Blocks.STONE.defaultBlockState());
            fill(level, landmark.offset(0, 1, -4), 96, 40, 8, Blocks.CONCRETE.pick(color).defaultBlockState());
            if (color == DyeColor.MAGENTA) {
                BlockPos background = center.offset(150, -1, side * 256);
                fill(level, background.offset(0, 0, -24), 96, 1, 48, Blocks.STONE.defaultBlockState());
                fill(level, background.offset(0, 1, -4), 96, 40, 8, Blocks.CONCRETE.pick(color).defaultBlockState());
            }
        }
    }

    private static void shaderImageDiagnostic(ClientGameTestContext context) {
        if (!Boolean.getBoolean("wormholes.clientgametest.dhImageDiagnostic")) {
            return;
        }
        for (int sample = 0; sample < 12; sample++) {
            String stage = "dh-shader-image-diagnostic-" + sample;
            context.runOnClient(minecraft -> DistantHorizonsTap.dumpAnimatedUniforms(stage));
            context.takeScreenshot(stage);
            context.waitTicks(60);
        }
    }

    private static BufferedImage sourceReference(ClientGameTestContext context, boolean shaders) {
        AssertionError lastFailure = null;
        for (int ticks = 0; ticks < LOD_TIMEOUT_TICKS; ticks += 20) {
            context.runOnClient(minecraft -> {
                assertTrue(DistantHorizonsTap.failure() == null, DistantHorizonsTap.failure());
                assertTrue(minecraft.level.dimension().equals(Level.OVERWORLD) && minecraft.player.level() == minecraft.level
                    && minecraft.player.position().distanceTo(new Vec3(1.5D, 70.0D, 26.5D)) < 0.1D
                    && minecraft.gameRenderer.mainCamera().position().distanceTo(minecraft.player.getEyePosition()) < 0.1D,
                    "DH source reference has the wrong physical player or client level");
            });
            BufferedImage reference = DhComposedImageAssertions.read(context.takeScreenshot("dh-source-floor-reference"));
            assertTrue(reference.getWidth() == 1920 && reference.getHeight() == 1080,
                "DH source reference requires a native 1920x1080 framebuffer");
            if (shaders) {
                return reference;
            }
            try {
                DhComposedImageAssertions.assertSourceBackground(reference, reference);
                context.runOnClient(minecraft -> DistantHorizonsTap.checkpoint("source-background-reference-ready"));
                return reference;
            } catch (AssertionError incomplete) {
                lastFailure = incomplete;
            }
            context.waitTicks(20);
        }
        throw new AssertionError("DH source-background reference did not become ready within " + LOD_TIMEOUT_TICKS + " ticks", lastFailure);
    }

    private static void seed(ClientGameTestContext context, TestSingleplayerContext world, ServerPlayer player,
                             ResourceKey<Level> level, BlockPos center) {
        TestServerContext server = world.getServer();
        for (int side : new int[]{-1, 1}) {
            Vec3 position = new Vec3(center.getX() + 20.5D, center.getY(), center.getZ() + side * 256 + 16.5D);
            seedLandmark(context, world, player, level, position, center.offset(-24, 0, side * 256 - 4));
            if (level.equals(Level.OVERWORLD)) {
                Vec3 background = new Vec3(center.getX() + 198.5D, center.getY(), position.z);
                seedLandmark(context, world, player, level, background, center.offset(150, 0, side * 256 - 4));
            }
        }
        Vec3 approach = new Vec3(center.getX() + 1.5D, center.getY(), center.getZ() + 6.5D);
        teleport(context, server, player, level, approach, 180.0F);
        awaitMain(context, "seed-" + level.identifier());
    }

    private static void seedLandmark(ClientGameTestContext context, TestSingleplayerContext world, ServerPlayer player,
                                     ResourceKey<Level> dimension, Vec3 position, BlockPos wall) {
        TestServerContext server = world.getServer();
        teleport(context, server, player, dimension, position, 180.0F);
        world.getConnection().waitForChunksDownload();
        world.getConnection().waitForChunksRender();
        BlockState expected = Blocks.CONCRETE.pick(dimension.equals(Level.OVERWORLD) ? DyeColor.MAGENTA : DyeColor.CYAN).defaultBlockState();
        CompletableFuture<?> lighting = server.computeOnServer(minecraftServer -> {
            ServerLevel level = Objects.requireNonNull(minecraftServer.getLevel(dimension));
            List<CompletableFuture<?>> tasks = new ArrayList<>();
            for (int chunkX = wall.getX() >> 4; chunkX <= (wall.getX() + 95) >> 4; chunkX++) {
                for (int chunkZ = wall.getZ() >> 4; chunkZ <= (wall.getZ() + 7) >> 4; chunkZ++) {
                    tasks.add(level.getChunkSource().getLightEngine().waitForPendingTasks(chunkX, chunkZ));
                }
            }
            return CompletableFuture.allOf(tasks.toArray(CompletableFuture<?>[]::new));
        });
        await(context, lighting, "DH landmark lighting " + wall);
        server.runOnServer(minecraftServer -> {
            ServerLevel level = Objects.requireNonNull(minecraftServer.getLevel(dimension));
            ServerLevelWrapper wrapper = ServerLevelWrapper.getWrapper(level);
            for (int x = 0; x < 96; x++) {
                for (int z = 0; z < 8; z++) {
                    for (int y = 0; y < 40; y++) {
                        BlockPos block = wall.offset(x, y, z);
                        assertTrue(level.getBlockState(block).equals(expected), "DH physical landmark differs at " + block);
                    }
                }
            }
            for (int chunkX = wall.getX() >> 4; chunkX <= (wall.getX() + 95) >> 4; chunkX++) {
                for (int chunkZ = wall.getZ() >> 4; chunkZ <= (wall.getZ() + 7) >> 4; chunkZ++) {
                    LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                    ChunkWrapper wrapped = new ChunkWrapper(chunk, wrapper);
                    int x = Math.max(wall.getX(), chunkX << 4);
                    int z = Math.max(wall.getZ(), chunkZ << 4);
                    assertTrue(chunk.isLightCorrect(), "DH landmark chunk lighting is incomplete: " + chunk.getPos());
                    assertTrue(wrapped.getMinNonEmptyHeight() <= wall.getY() && wrapped.getMaxNonEmptyHeight() > wall.getY() + 39,
                        "DH source wrapper excludes the physical landmark height: " + chunk.getPos());
                    assertTrue(expected.equals(wrapped.getBlockState(x & 15, wall.getY() + 39, z & 15).getWrappedMcObject()),
                        "DH source wrapper differs from the real landmark: " + chunk.getPos());
                }
            }
            System.out.println("WORMHOLES_DH_LANDMARK_PHYSICAL_PASS dimension=" + dimension.identifier() + " wall=" + wall
                + " blocks=30720 state=" + expected + " minY=" + wrapper.getMinHeight() + " maxY=" + wrapper.getMaxHeight()
                + " lighting-complete=true wrapper-state-and-height=true");
        });
        awaitClientLandmark(context, dimension, wall, expected);
        awaitLandmarkUpdates(context, server, dimension, wall);
        server.runOnServer(minecraftServer -> saveLandmark(minecraftServer, dimension, wall));
        awaitRecordedLandmark(context, server, dimension, wall, expected);
        context.waitTicks(SETTLE_TICKS);
    }

    private static void awaitClientLandmark(ClientGameTestContext context, ResourceKey<Level> dimension,
                                            BlockPos wall, BlockState expected) {
        context.waitFor(minecraft -> {
            assertTrue(DistantHorizonsTap.failure() == null, DistantHorizonsTap.failure());
            assertTrue(minecraft.level.dimension().equals(dimension) && minecraft.player.level() == minecraft.level,
                "DH seed client player and level differ from the physical dimension");
            for (int x = 0; x < 96; x++) {
                for (int z = 0; z < 8; z++) {
                    for (int y = 0; y < 40; y++) {
                        if (!minecraft.level.getBlockState(wall.offset(x, y, z)).equals(expected)) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }, LOD_TIMEOUT_TICKS);
        System.out.println("WORMHOLES_DH_LANDMARK_CLIENT_PASS dimension=" + dimension.identifier() + " wall=" + wall
            + " blocks=30720 state=" + expected + " actual-client-blocks=true");
    }

    private static void saveLandmark(MinecraftServer server, ResourceKey<Level> dimension, BlockPos wall) {
        ServerLevel level = Objects.requireNonNull(server.getLevel(dimension));
        ServerLevelWrapper wrapper = ServerLevelWrapper.getWrapper(level);
        int alreadySaved = 0;
        int chunks = 0;
        for (int chunkX = wall.getX() >> 4; chunkX <= (wall.getX() + 95) >> 4; chunkX++) {
            for (int chunkZ = wall.getZ() >> 4; chunkZ <= (wall.getZ() + 7) >> 4; chunkZ++) {
                LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                assertTrue(chunk.isLightCorrect() && !chunk.isUpgrading(),
                    "DH landmark cannot be recorded by the normal chunk save: " + chunk.getPos());
                assertTrue(!SharedApi.isChunkAtChunkPosAlreadyUpdating(wrapper, chunkX, chunkZ),
                    "DH landmark was queued again before the normal chunk save: " + chunk.getPos());
                if (!chunk.isUnsaved()) {
                    alreadySaved++;
                }
                chunk.markUnsaved();
                chunks++;
            }
        }
        level.getChunkSource().save(true);
        for (int chunkX = wall.getX() >> 4; chunkX <= (wall.getX() + 95) >> 4; chunkX++) {
            for (int chunkZ = wall.getZ() >> 4; chunkZ <= (wall.getZ() + 7) >> 4; chunkZ++) {
                assertTrue(!level.getChunk(chunkX, chunkZ).isUnsaved(),
                    "DH landmark normal chunk save did not save chunk: " + chunkX + "," + chunkZ);
            }
        }
        System.out.println("WORMHOLES_DH_LANDMARK_SAVE_PASS dimension=" + dimension.identifier() + " wall=" + wall
            + " chunks=" + chunks + " already-saved=" + alreadySaved + " normal-native-save=true");
    }

    private static void awaitLandmarkUpdates(ClientGameTestContext context, TestServerContext server, ResourceKey<Level> dimension, BlockPos wall) {
        for (int ticks = 0; ticks < LOD_TIMEOUT_TICKS; ticks += 20) {
            boolean updating = server.computeOnServer(minecraftServer -> {
                ServerLevelWrapper wrapper = ServerLevelWrapper.getWrapper(Objects.requireNonNull(minecraftServer.getLevel(dimension)));
                for (int chunkX = wall.getX() >> 4; chunkX <= (wall.getX() + 95) >> 4; chunkX++) {
                    for (int chunkZ = wall.getZ() >> 4; chunkZ <= (wall.getZ() + 7) >> 4; chunkZ++) {
                        if (SharedApi.isChunkAtChunkPosAlreadyUpdating(wrapper, chunkX, chunkZ)) {
                            return true;
                        }
                    }
                }
                return false;
            });
            if (!updating) {
                return;
            }
            context.waitTicks(20);
        }
        throw new AssertionError("DH landmark updates did not drain before the physical chunk save: " + wall);
    }

    private static void awaitRecordedLandmark(ClientGameTestContext context, TestServerContext server, ResourceKey<Level> dimension,
                                              BlockPos wall, BlockState expected) {
        LandmarkRecording serverRecording = server.computeOnServer(minecraftServer -> {
            ServerLevelWrapper wrapper = ServerLevelWrapper.getWrapper(Objects.requireNonNull(minecraftServer.getLevel(dimension)));
            return new LandmarkRecording(Objects.requireNonNull(wrapper.getDhLevel(), "DH seed level is unavailable").getFullDataProvider(),
                wrapper.getMinHeight(), wrapper.getDhIdentifier());
        });
        LandmarkRecording recording = null;
        String missing = "native renderer has not selected the physical landmark level";
        for (int ticks = 0; ticks < LOD_TIMEOUT_TICKS; ticks += 20) {
            LandmarkRecording selected = context.computeOnClient(minecraft -> renderingLandmark(minecraft, dimension, serverRecording.minY()));
            if (selected != null) {
                if (recording == null || selected.provider() != recording.provider()) {
                    recording = selected;
                    context.runOnClient(minecraft -> {
                        RenderParams parameters = DhPortalClientApiAccess.wormholes$renderParams();
                        System.out.println("WORMHOLES_DH_LANDMARK_RENDER_SELECTION environment=" + SharedApi.getEnvironment()
                            + " world-id=" + System.identityHashCode(parameters.dhClientWorld)
                            + " level=" + parameters.dhClientLevel.getClass().getSimpleName()
                            + " level-id=" + System.identityHashCode(parameters.dhClientLevel));
                    });
                    System.out.println("WORMHOLES_DH_LANDMARK_PROVIDER_PASS source=" + recording.identifier()
                        + " provider=" + recording.provider().getClass().getSimpleName()
                        + " provider-id=" + System.identityHashCode(recording.provider())
                        + " database=" + recording.provider().repo.databaseFile + " minY=" + recording.minY()
                        + " server-source=" + serverRecording.identifier()
                        + " server-provider-id=" + System.identityHashCode(serverRecording.provider())
                        + " server-database=" + serverRecording.provider().repo.databaseFile
                        + " identifiers-equal=" + recording.identifier().equals(serverRecording.identifier())
                        + " providers-identical=" + (recording.provider() == serverRecording.provider()));
                }
                missing = recordedLandmarkMismatch(context, recording.provider(), recording.minY(), wall, expected);
                LandmarkRecording current = context.computeOnClient(minecraft -> renderingLandmark(minecraft, dimension, serverRecording.minY()));
                if (current == null || current.provider() != recording.provider()) {
                    missing = "native renderer selection changed during the recorded column read";
                } else if (missing == null) {
                    System.out.println("WORMHOLES_DH_LANDMARK_RECORDED_PASS dimension=" + dimension.identifier()
                        + " wall=" + wall + " state=" + expected + " blocks=30720 columns=768 height=40 source=" + recording.identifier()
                        + " database=" + recording.provider().repo.databaseFile + " actual-rendering-provider=true");
                    return;
                }
            } else {
                missing = "native renderer has not selected the physical landmark level";
            }
            context.waitTicks(20);
        }
        server.runOnServer(minecraftServer -> landmarkRecordingDiagnostics(minecraftServer, dimension, wall));
        context.runOnClient(minecraft -> {
            RenderParams parameters = DhPortalClientApiAccess.wormholes$renderParams();
            System.err.println("WORMHOLES_DH_LANDMARK_RENDERER_DIAGNOSTIC environment=" + SharedApi.getEnvironment()
                + " world=" + SharedApi.getAbstractDhWorld() + " state-wrapper=" + ClientApi.RENDER_STATE.clientLevelWrapper
                + " params-wrapper=" + parameters.clientLevelWrapper + " params-level=" + parameters.dhClientLevel
                + " params-world=" + parameters.dhClientWorld + " validation=" + ClientApi.INSTANCE.lastRenderParamValidationMessage);
        });
        throw new AssertionError("DH did not record the physical landmark within " + LOD_TIMEOUT_TICKS + " ticks: source="
            + (recording == null ? "unselected" : recording.identifier())
            + " database=" + (recording == null ? "unselected" : recording.provider().repo.databaseFile)
            + " minY=" + serverRecording.minY() + " " + missing);
    }

    private static LandmarkRecording renderingLandmark(Minecraft minecraft, ResourceKey<Level> dimension, int minY) {
        assertTrue(minecraft.level.dimension().equals(dimension) && minecraft.player.level() == minecraft.level,
            "DH recording read differs from the physical client dimension");
        IClientLevelWrapper wrapper = Objects.requireNonNull(ClientLevelWrapper.getWrapper(minecraft.level));
        assertTrue(wrapper.getWrappedMcObject() == minecraft.level && wrapper.getMinHeight() == minY,
            "DH rendering wrapper differs from the physical client level or minY");
        RenderParams parameters = DhPortalClientApiAccess.wormholes$renderParams();
        if (ClientApi.RENDER_STATE.clientLevelWrapper != wrapper || parameters.clientLevelWrapper != wrapper
            || parameters.dhClientWorld == null || parameters.dhClientLevel == null || parameters.exactCameraPosition == null
            || parameters.dhClientWorld != SharedApi.tryGetDhClientWorld()
            || minecraft.player.getEyePosition().distanceTo(new Vec3(parameters.exactCameraPosition.x,
                parameters.exactCameraPosition.y, parameters.exactCameraPosition.z)) > 0.1D) {
            return null;
        }
        IDhClientLevel level = parameters.dhClientLevel;
        assertTrue(parameters.dhClientWorld.getLevel(wrapper) == level && wrapper.getDhLevel() == level
            && level.getClientLevelWrapper() == wrapper,
            "DH native render parameters and active world disagree on the physical client level");
        FullDataSourceProviderV2 provider = Objects.requireNonNull(level.getFullDataProvider(), "DH rendering provider is unavailable");
        return new LandmarkRecording(provider, wrapper.getMinHeight(), wrapper.getDhIdentifier());
    }

    private static void landmarkRecordingDiagnostics(MinecraftServer server, ResourceKey<Level> dimension, BlockPos wall) {
        ServerLevel level = Objects.requireNonNull(server.getLevel(dimension));
        ServerLevelWrapper wrapper = ServerLevelWrapper.getWrapper(level);
        for (int chunkX = wall.getX() >> 4; chunkX <= (wall.getX() + 95) >> 4; chunkX++) {
            for (int chunkZ = wall.getZ() >> 4; chunkZ <= (wall.getZ() + 7) >> 4; chunkZ++) {
                LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                ChunkWrapper wrapped = new ChunkWrapper(chunk, wrapper);
                wrapped.createDhHeightMaps();
                System.err.println("WORMHOLES_DH_LANDMARK_RECORDING_DIAGNOSTIC source=" + wrapper.getDhIdentifier()
                    + " chunk=" + chunk.getPos() + " unsaved=" + chunk.isUnsaved() + " light-correct=" + chunk.isLightCorrect()
                    + " updating=" + SharedApi.isChunkAtChunkPosAlreadyUpdating(wrapper, chunkX, chunkZ)
                    + " physical-hash=" + wrapped.getBlockBiomeHashCode()
                    + " recorded-hash=" + Objects.requireNonNull(wrapper.getDhLevel()).getChunkHash(wrapped.getChunkPos())
                    + " generator-plan=" + wrapper.getDhLevel().getFullDataProvider().getGeneratorPlan());
            }
        }
    }

    private static String recordedLandmarkMismatch(ClientGameTestContext context, FullDataSourceProviderV2 provider, int minY, BlockPos wall, BlockState expected) {
        for (int sectionX = wall.getX() >> 6; sectionX <= (wall.getX() + 95) >> 6; sectionX++) {
            for (int sectionZ = wall.getZ() >> 6; sectionZ <= (wall.getZ() + 7) >> 6; sectionZ++) {
                long section = DhSectionPos.encode(FullDataSourceProviderV2.LEAF_SECTION_DETAIL_LEVEL, sectionX, sectionZ);
                try (FullDataSourceV2 source = await(context, context.computeOnClient(minecraft -> provider.getAsync(section)), "DH recorded landmark read")) {
                    if (source == null) {
                        return "missing section=" + DhSectionPos.toString(section);
                    }
                    assertTrue(source.getPos() == section, "DH recorded landmark returned a different leaf section");
                    int minX = Math.max(wall.getX(), sectionX << 6);
                    int maxX = Math.min(wall.getX() + 95, (sectionX << 6) + 63);
                    int minZ = Math.max(wall.getZ(), sectionZ << 6);
                    int maxZ = Math.min(wall.getZ() + 7, (sectionZ << 6) + 63);
                    for (int x = minX; x <= maxX; x++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            int relativeX = x & 63;
                            int relativeZ = z & 63;
                            LongArrayList column = source.getColumnAtRelPos(relativeX, relativeZ);
                            for (int y = wall.getY(); y < wall.getY() + 40; y++) {
                                long point = source.getDataPointAtBlockPos(x, y, z, minY);
                                assertTrue(point == recordedColumnPoint(column, y - minY),
                                    "DH block-position read differs from its actual leaf column at " + new BlockPos(x, y, z));
                                if (point == 0L || !expected.equals(source.mapping.getBlockStateWrapper(FullDataPointUtil.getId(point)).getWrappedMcObject())) {
                                    int index = FullDataSourceV2.relativePosToIndex(relativeX, relativeZ);
                                    return "block=" + new BlockPos(x, y, z) + " section=" + DhSectionPos.toString(source.getPos())
                                        + " relative=" + relativeX + "," + relativeZ + " point=" + FullDataPointUtil.toString(point)
                                        + " state=" + (point == 0L ? "missing" : source.mapping.getBlockStateWrapper(FullDataPointUtil.getId(point)).getWrappedMcObject())
                                        + " generation-step=" + source.columnGenerationSteps.getByte(index)
                                        + " compression=" + source.columnWorldCompressionMode.getByte(index)
                                        + " last-modified=" + source.lastModifiedUnixDateTime + " direct-column-agrees=true";
                                }
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private static long recordedColumnPoint(LongArrayList column, int relativeY) {
        if (column == null) {
            return 0L;
        }
        for (int i = 0; i < column.size(); i++) {
            long point = column.getLong(i);
            int bottom = FullDataPointUtil.getBottomY(point);
            if (point != 0L && relativeY >= bottom && relativeY < bottom + FullDataPointUtil.getHeight(point)) {
                return point;
            }
        }
        return 0L;
    }

    private static <T> T await(ClientGameTestContext context, CompletableFuture<T> task, String operation) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LANDMARK_TASK_TIMEOUT_SECONDS);
        try {
            while (!task.isDone()) {
                if (System.nanoTime() >= deadline) {
                    throw new TimeoutException(operation);
                }
                context.waitTicks(1);
            }
            return task.get(0, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Error error) {
                throw error;
            }
            if (cause instanceof RuntimeException error) {
                throw error;
            }
            throw new AssertionError(operation + " failed", cause);
        } catch (TimeoutException failure) {
            throw new AssertionError(operation + " exceeded " + LANDMARK_TASK_TIMEOUT_SECONDS + " seconds", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(operation + " was interrupted", failure);
        }
    }

    private static UUID[] mirrors(MinecraftServer server, ServerPlayer player, List<UUID> created) {
        UUID[] mirrors = new UUID[3];
        BlockPos[] origins = {FIRST_MIRROR, SECOND_MIRROR, NESTED_MIRROR};
        WormholesModRuntime runtime = runtime(server);
        for (int index = 0; index < origins.length; index++) {
            int width = index == 2 ? 33 : 3;
            List<BlockPos> aperture = new ArrayList<>(width * 3);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < 3; y++) {
                    aperture.add(origins[index].offset(x, y, 0));
                }
            }
            MinecraftPortal mirror = runtime.portals().create(player.getUUID(), server.overworld(), aperture,
                PortalType.PORTAL, new Vec3(0, 0, index == 2 ? 1 : -1));
            mirror.setMirrorMode(true);
            runtime.portals().save(mirror);
            created.add(mirror.getId());
            mirrors[index] = mirror.getId();
        }
        return mirrors;
    }

    private static void revisit(ClientGameTestContext context, TestServerContext server, ServerPlayer player, UUID[] mirrors,
                                boolean shaders) {
        RenderBufferHandler firstBuffers = context.computeOnClient(minecraft -> DistantHorizonsTap.buffers(FIRST_MIRROR, 1));
        RenderBufferHandler secondBuffers = context.computeOnClient(minecraft -> DistantHorizonsTap.buffers(SECOND_MIRROR, 1));
        DistantHorizonsTap.History first = context.computeOnClient(minecraft -> DistantHorizonsTap.history(FIRST_MIRROR, 1));
        DistantHorizonsTap.History second = context.computeOnClient(minecraft -> DistantHorizonsTap.history(SECOND_MIRROR, 1));
        Map<DistantHorizonsTap.HistoryPath, DistantHorizonsTap.History> nested =
            context.computeOnClient(minecraft -> DistantHorizonsTap.nestedHistorySnapshot());
        IrisPipelineCostTap.Counts original = shaders ? context.computeOnClient(minecraft -> IrisPipelineCostTap.snapshot()) : null;
        for (int visit = 0; visit < 3; visit++) {
            projection(server, player, mirrors, false);
            context.waitTicks(20);
            long previous = context.computeOnClient(minecraft -> DistantHorizonsTap.visibleSamples(FIRST_MIRROR, 1));
            projection(server, player, mirrors, true);
            context.getInput().lookAt(visit == 1 ? 185.0F : 180.0F, 0.0F);
            awaitLod(context, FIRST_MIRROR, 1, "mirror-revisit-" + visit, previous);
            boolean samePose = visit != 1;
            context.runOnClient(minecraft -> DistantHorizonsTap.assertNestedHistoryStable(nested, samePose));
            context.runOnClient(minecraft -> assertTrue(DistantHorizonsTap.buffers(FIRST_MIRROR, 1) == firstBuffers
                && DistantHorizonsTap.buffers(SECOND_MIRROR, 1) == secondBuffers, "DH mirror revisit replaced terrain buffers"));
            if (context.computeOnClient(minecraft -> DistantHorizonsTap.temporalHistorySupported())
                && !Boolean.getBoolean("wormholes.clientgametest.baseline")) {
                context.runOnClient(minecraft -> {
                    stable(first, DistantHorizonsTap.history(FIRST_MIRROR, 1), "first mirror revisit");
                    stable(second, DistantHorizonsTap.history(SECOND_MIRROR, 1), "second mirror revisit");
                });
                verifyHistory(context);
            }
        }
        if (shaders && !Boolean.getBoolean("wormholes.clientgametest.baseline")) {
            context.runOnClient(minecraft -> assertTrue(IrisPipelineCostTap.snapshot().creations() == original.creations(),
                "Warmed DH mirror revisits compiled new Iris pipelines"));
        }
    }

    private static void expire(ClientGameTestContext context, TestServerContext server, ServerPlayer player, UUID[] mirrors) {
        RenderBufferHandler firstBuffers = context.computeOnClient(minecraft -> DistantHorizonsTap.buffers(FIRST_MIRROR, 1));
        RenderBufferHandler mainBuffers = context.computeOnClient(minecraft -> DistantHorizonsTap.buffers(BlockPos.ZERO, 0));
        DistantHorizonsTap.History first = context.computeOnClient(minecraft -> DistantHorizonsTap.history(FIRST_MIRROR, 1));
        DistantHorizonsTap.History second = context.computeOnClient(minecraft -> DistantHorizonsTap.history(SECOND_MIRROR, 1));
        DistantHorizonsTap.History main = context.computeOnClient(minecraft -> DistantHorizonsTap.mainHistory());
        boolean temporalHistory = context.computeOnClient(minecraft -> DistantHorizonsTap.temporalHistorySupported());
        projection(server, player, mirrors, false);
        long started = System.nanoTime();
        int initialDraws = context.computeOnClient(minecraft -> DistantHorizonsTap.draws());
        while (System.nanoTime() - started < 65_000_000_000L) {
            context.waitTicks(20);
            context.runOnClient(minecraft -> assertTrue(DistantHorizonsTap.failure() == null, DistantHorizonsTap.failure()));
        }
        context.runOnClient(minecraft -> assertTrue(DistantHorizonsTap.draws() > initialDraws,
            "Hidden DH GPU frames did not keep drawing during real cache expiry"));
        if (temporalHistory) {
            context.runOnClient(minecraft -> {
                released(first, "first mirror expiry");
                released(second, "second mirror expiry");
                stable(main, DistantHorizonsTap.mainHistory(), "main view during portal expiry");
                live(main, "main view during portal expiry");
                System.out.println("WORMHOLES_DH_HISTORY_EXPIRY_PASS portal-textures-released=true main-retained=true");
            });
        }
        long before = context.computeOnClient(minecraft -> DistantHorizonsTap.visibleSamples(FIRST_MIRROR, 1));
        projection(server, player, mirrors, true);
        context.getInput().lookAt(180.0F, 0.0F);
        awaitLod(context, FIRST_MIRROR, 1, "mirror-after-expiry", before);
        context.waitFor(minecraft -> DistantHorizonsTap.buffers(FIRST_MIRROR, 1) != firstBuffers, TRAVEL_TIMEOUT_TICKS);
        context.runOnClient(minecraft -> assertTrue(DistantHorizonsTap.buffers(BlockPos.ZERO, 0) == mainBuffers,
            "DH portal expiry replaced the main terrain buffers"));
        if (temporalHistory) {
            context.waitFor(minecraft -> DistantHorizonsTap.history(FIRST_MIRROR, 1) != null
                && DistantHorizonsTap.history(FIRST_MIRROR, 1).pipeline() != first.pipeline(), TRAVEL_TIMEOUT_TICKS);
            verifyHistory(context);
            DistantHorizonsTap.History replacement = context.computeOnClient(minecraft -> DistantHorizonsTap.history(FIRST_MIRROR, 1));
            context.waitTicks(20);
            context.runOnClient(minecraft -> stable(replacement, DistantHorizonsTap.history(FIRST_MIRROR, 1), "expired replacement"));
        }
        context.takeScreenshot("dh-mirror-after-expiry");
    }

    private static void verifyHistory(ClientGameTestContext context) {
        context.waitFor(minecraft -> rendered(DistantHorizonsTap.mainHistory())
            && rendered(DistantHorizonsTap.history(FIRST_MIRROR, 1))
            && rendered(DistantHorizonsTap.history(SECOND_MIRROR, 1))
            && nestedHistoriesRendered(), TRAVEL_TIMEOUT_TICKS);
        context.runOnClient(minecraft -> {
            DistantHorizonsTap.History main = DistantHorizonsTap.mainHistory();
            DistantHorizonsTap.History first = DistantHorizonsTap.history(FIRST_MIRROR, 1);
            DistantHorizonsTap.History second = DistantHorizonsTap.history(SECOND_MIRROR, 1);
            List<DistantHorizonsTap.History> nested = DistantHorizonsTap.histories(NESTED_MIRROR, 2);
            assertTrue(first.frame() == second.frame() && first.frame() == main.frame(),
                "DH mirrors and the main view were not rendered in the same frame");
            live(main, "main view");
            live(first, "first mirror");
            live(second, "second mirror");

            isolated(main, first);
            isolated(main, second);
            isolated(first, second);
            for (int index = 0; index < nested.size(); index++) {
                DistantHorizonsTap.History history = nested.get(index);
                live(history, "nested mirror camera " + index);
                isolated(main, history);
                isolated(first, history);
                isolated(second, history);
                for (int other = index + 1; other < nested.size(); other++) {
                    isolated(history, nested.get(other));
                }
            }
            System.out.println("WORMHOLES_DH_NESTED_HISTORY_PASS distinct-cameras=" + nested.size());
        });
    }

    private static boolean nestedHistoriesRendered() {
        List<DistantHorizonsTap.History> histories = DistantHorizonsTap.histories(NESTED_MIRROR, 2);
        if (histories.size() < 2) {
            return false;
        }
        for (DistantHorizonsTap.History history : histories) {
            if (!rendered(history)) {
                return false;
            }
        }
        return true;
    }

    private static void verifyTerrainBuffers(ClientGameTestContext context) {
        context.waitFor(minecraft -> {
            RenderBufferHandler main = DistantHorizonsTap.buffers(BlockPos.ZERO, 0);
            RenderBufferHandler first = DistantHorizonsTap.buffers(FIRST_MIRROR, 1);
            RenderBufferHandler second = DistantHorizonsTap.buffers(SECOND_MIRROR, 1);
            RenderBufferHandler nested = DistantHorizonsTap.buffers(NESTED_MIRROR, 2);
            return main != null && first != null && second != null && nested != null
                && main != first && main != second && main != nested && first != second && first != nested && second != nested;
        }, TRAVEL_TIMEOUT_TICKS);
    }

    private static boolean rendered(DistantHorizonsTap.History history) {
        return history != null && history.renders() >= 8;
    }

    private static void isolated(DistantHorizonsTap.History first, DistantHorizonsTap.History second) {
        assertTrue(first.pipeline() != second.pipeline(), "DH views share mutable temporal state");
        assertTrue(first.first() != second.first() && first.first() != second.second()
            && first.second() != second.first() && first.second() != second.second(),
            "DH views share actual temporal textures");
    }

    private static void live(DistantHorizonsTap.History history, String label) {
        assertTrue(history != null && history.first() > 0 && history.second() > 0
            && history.first() != history.second() && GL11C.glIsTexture(history.first())
            && GL11C.glIsTexture(history.second()), label + " lacks two live temporal textures");
    }

    private static void released(DistantHorizonsTap.History history, String label) {
        assertTrue(history != null, label + " has no sampled temporal history");
        if (history.resources() != null) {
            assertTrue(history.resources().released(), label + " retained a sampled native temporal resource: pipeline="
                + System.identityHashCode(history.pipeline()) + " first=" + history.first() + " second=" + history.second()
                + " resources=" + history.resources().describe());
            System.out.println("WORMHOLES_DH_HISTORY_RESOURCE_RELEASE_PASS label=" + label + " pipeline="
                + System.identityHashCode(history.pipeline()) + " first=" + history.first() + " second=" + history.second()
                + " resources=" + history.resources().describe());
            return;
        }
        assertTrue(!GL11C.glIsTexture(history.first()) && !GL11C.glIsTexture(history.second()),
            label + " retained an expired temporal texture");
    }

    private static void stable(DistantHorizonsTap.History before, DistantHorizonsTap.History after, String label) {
        assertTrue(before != null && after != null && before.pipeline() == after.pipeline()
            && before.first() == after.first() && before.second() == after.second(), label + " replaced its pipeline or history textures");
    }

    private static void awaitNested(ClientGameTestContext context) {
        context.waitFor(minecraft -> DistantHorizonsTap.failure() != null
            || DistantHorizonsTap.visibleSamples(NESTED_MIRROR, 2) > 0L, LOD_TIMEOUT_TICKS);
        context.runOnClient(minecraft -> {
            assertTrue(DistantHorizonsTap.failure() == null, DistantHorizonsTap.failure());
            DistantHorizonsTap.checkpoint("recursive-mirror-distant-terrain");
        });
    }

    private static void awaitMain(ClientGameTestContext context, String label) {
        long before = context.computeOnClient(minecraft -> DistantHorizonsTap.visibleSamples(BlockPos.ZERO, 0));
        awaitLod(context, BlockPos.ZERO, 0, label, before);
    }

    private static void awaitLod(ClientGameTestContext context, BlockPos origin, int depth, String label, long previous) {
        context.runOnClient(minecraft -> DistantHorizonsTap.checkpoint("waiting-" + label));
        try {
            context.waitFor(minecraft -> DistantHorizonsTap.failure() != null
                || DistantHorizonsTap.visibleSamples(origin, depth) > previous, LOD_TIMEOUT_TICKS);
        } catch (AssertionError failure) {
            context.runOnClient(minecraft -> {
                DistantHorizonsTap.checkpoint("timeout-" + label);
                System.err.println("DH timeout client level=" + minecraft.level.dimension() + " player=" + minecraft.player.position()
                    + " camera=" + minecraft.gameRenderer.mainCamera().position() + " yaw=" + minecraft.player.getYRot());
            });
            context.takeScreenshot("dh-timeout-" + label);
            throw failure;
        }
        context.runOnClient(minecraft -> {
            assertTrue(DistantHorizonsTap.failure() == null, DistantHorizonsTap.failure());
            DistantHorizonsTap.checkpoint(label);
        });
    }

    private static void projection(TestServerContext server, ServerPlayer player, UUID[] mirrors, boolean enabled) {
        server.runOnServer(minecraftServer -> {
            WormholesModRuntime runtime = runtime(minecraftServer);
            for (UUID id : mirrors) {
                MinecraftPortal mirror = Objects.requireNonNull(runtime.portals().get(id));
                mirror.setProjectionMode(enabled ? ProjectionMode.ON : ProjectionMode.OFF);
                runtime.portals().save(mirror);
            }
        });
    }

    private static void teleport(ClientGameTestContext context, TestServerContext server, ServerPlayer player,
                                 ResourceKey<Level> dimension, Vec3 position, float yaw) {
        server.runOnServer(minecraftServer -> {
            player.teleportTo(Objects.requireNonNull(minecraftServer.getLevel(dimension)), position.x, position.y, position.z,
                Set.of(), yaw, 0.0F, false);
            player.setDeltaMovement(Vec3.ZERO);
        });
        context.waitFor(minecraft -> minecraft.level.dimension().equals(dimension)
            && minecraft.player.position().distanceTo(position) < 0.1D, TRAVEL_TIMEOUT_TICKS);
        context.getInput().lookAt(yaw, 0.0F);
        context.runOnClient(DistantHorizonsClientGameTest::assertHidden);
    }

    private static void assertHidden(Minecraft client) {
        assertTrue(Boolean.getBoolean("automator.hidden") && NativeTestWindow.hiddenAndUnfocused(client), "DH acceptance requires a hidden, unfocused native GPU client");
        assertTrue(NativeTestWindow.mouseReleased(client), "DH acceptance grabbed the desktop mouse");
        assertTrue(client.gameRenderer.mainRenderTarget().width == 1920 && client.gameRenderer.mainRenderTarget().height == 1080,
            "DH acceptance requires a native 1920x1080 GPU framebuffer");
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

    private record LandmarkRecording(FullDataSourceProviderV2 provider, int minY, String identifier) {
    }

    private record Options(CameraType camera, boolean hidden, int renderDistance, boolean recursion, int lodDistance) {
    }
}
