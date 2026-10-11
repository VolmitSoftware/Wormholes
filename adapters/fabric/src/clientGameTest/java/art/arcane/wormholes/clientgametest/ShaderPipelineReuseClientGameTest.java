package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.iris.IrisViewPipelines;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
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
import org.lwjgl.opengl.GL11C;

import java.util.Set;
import java.util.UUID;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.cells;
import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;

public final class ShaderPipelineReuseClientGameTest implements FabricClientGameTest {
    private static final int TIMEOUT_TICKS = 600;
    private static final int IDLE_TIMEOUT_TICKS = 1800;
    private static final int OBSERVATION_TICKS = 30;
    private static final int MIN_RENDERED_FRAMES = 8;
    private static final int REVISITS = 3;
    private static final long QUICK_REVISIT_NANOS = 55_000_000_000L;
    private static final long IDLE_NANOS = 65_000_000_000L;
    private static final long MIN_RELEASE_IDLE_NANOS = 59_000_000_000L;
    private static final BlockPos FIRST = new BlockPos(40, 70, 20);
    private static final BlockPos SECOND = new BlockPos(46, 70, 23);
    private static final Vec3 POSITION = new Vec3(44.5D, 70.0D, 30.5D);
    private static final boolean BASELINE = Boolean.getBoolean("wormholes.clientgametest.baseline");

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enable();
        context.runOnClient(client -> {
            IrisViewPipelines.clear();
            IrisPipelineCostTap.start();
        });
        IrisPipelineCostTap.Counts total = context.computeOnClient(client -> IrisPipelineCostTap.snapshot());
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getConnection().waitForChunksDownload();
            context.waitFor(client -> WormholesClient.instance() != null && WormholesClient.instance().session().active(), TIMEOUT_TICKS);
            context.runOnClient(client -> {
                assertTrue(Iris.isPackInUseQuick() && Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline,
                    "shader pipeline cost acceptance requires an active Iris shader pipeline");
                System.out.println("Iris pipeline reuse mode=" + (BASELINE ? "baseline-comparison" : "current-assertions")
                    + " pack=" + Iris.getCurrentPackName() + " revisits=" + REVISITS + " idleSeconds=65");
            });
            ServerPlayer player = singleplayer.getServer().computeOnServer(server -> singleplayer.getConnection().getServerPlayer());
            UUID[] mirrors = singleplayer.getServer().computeOnServer(server -> build(server, player));
            try {
                singleplayer.getServer().runOnServer(server -> {
                    player.setGameMode(GameType.CREATIVE);
                    player.getAbilities().flying = false;
                    player.onUpdateAbilities();
                    player.teleportTo(player.level(), POSITION.x, POSITION.y, POSITION.z, Set.of(), 180.0F, 0.0F, false);
                    player.setDeltaMovement(Vec3.ZERO);
                });
                context.waitFor(client -> client.player.position().distanceTo(POSITION) < 0.05D, TIMEOUT_TICKS);
                context.getInput().lookAt(180.0F, 0.0F);
                singleplayer.getConnection().waitForChunksRender();
                resetHistory(context);
                context.waitFor(client -> IrisHistoryTap.failure() != null
                    || IrisHistoryTap.main() != null && IrisHistoryTap.main().renders() >= MIN_RENDERED_FRAMES, TIMEOUT_TICKS);
                IrisHistoryTap.Sample main = context.computeOnClient(client -> {
                    assertTrue(IrisHistoryTap.failure() == null, IrisHistoryTap.failure());
                    IrisPipelineCostTap.log("world-preparation", total);
                    return IrisHistoryTap.main();
                });
                IrisPipelineCostTap.Counts firstCost = context.computeOnClient(client -> IrisPipelineCostTap.snapshot());
                projection(singleplayer, mirrors, ProjectionMode.ON);
                awaitViews(context);
                Views original = context.computeOnClient(client -> views(main, "first-open"));
                context.runOnClient(client -> {
                    IrisPipelineCostTap.Counts delta = IrisPipelineCostTap.log("first-open", firstCost);
                    assertTrue(delta.creations() >= 2L && delta.links() > 0L && delta.constructionNanos() > 0L,
                        "independent constructor/link taps did not observe both real portal pipelines");
                    if (!BASELINE) {
                        assertTrue(delta.drawCreations() == 0L, "first portal pipelines were constructed inside portal draw");
                    }
                });
                motion(context, main, original, "first-motion");
                context.takeScreenshot("shader-pipeline-reuse-two-mirrors");
                Views previous = original;
                for (int cycle = 1; cycle <= REVISITS; cycle++) {
                    previous = revisit(context, singleplayer, mirrors, main, original, previous, cycle);
                }
                expire(context, singleplayer, mirrors, main, previous);
                context.runOnClient(client -> IrisPipelineCostTap.log("fixture-total", total));
            } finally {
                context.runOnClient(client -> IrisHistoryTap.stop());
                singleplayer.getServer().runOnServer(server -> {
                    WormholesModRuntime runtime = runtime(server);
                    for (UUID id : mirrors) {
                        runtime.portals().remove(player, id);
                    }
                });
            }
        } finally {
            context.runOnClient(client -> IrisPipelineCostTap.stop());
        }
    }

    private static Views revisit(ClientGameTestContext context, TestSingleplayerContext singleplayer, UUID[] mirrors,
                                 IrisHistoryTap.Sample main, Views original, Views previous, int cycle) {
        IrisPipelineCostTap.Counts cost = context.computeOnClient(client -> IrisPipelineCostTap.snapshot());
        long closed = System.nanoTime();
        projection(singleplayer, mirrors, ProjectionMode.OFF);
        awaitClosed(context);
        context.waitTicks(OBSERVATION_TICKS);
        context.runOnClient(client -> {
            IrisPipelineCostTap.log("off-" + cycle, cost);
            if (!BASELINE) {
                assertTrue(!IrisPipelineCostTap.destroyed(original.first().pipeline())
                    && !IrisPipelineCostTap.destroyed(original.second().pipeline()), "closing a view released retained pipelines before idle expiry");
            }
        });
        resetHistory(context);
        projection(singleplayer, mirrors, ProjectionMode.ON);
        awaitViews(context);
        assertTrue(System.nanoTime() - closed < QUICK_REVISIT_NANOS, "quick revisit exceeded its 55-second wall-clock bound");
        Views reopened = context.computeOnClient(client -> {
            Views next = views(main, "revisit-" + cycle);
            assertTrue(next.firstView() != previous.firstView() && next.secondView() != previous.secondView(),
                "projection off/on did not close and recreate both PortalView objects");
            IrisPipelineCostTap.Counts delta = IrisPipelineCostTap.log("revisit-" + cycle, cost);
            if (!BASELINE) {
                stable(original, next, "quick revisit " + cycle);
                assertTrue(delta.creations() == 0L && delta.destructions() == 0L,
                    "quick revisit reconstructed or destroyed an Iris pipeline before expiry");
            }
            return next;
        });
        motion(context, main, reopened, "revisit-motion-" + cycle);
        return reopened;
    }

    private static void expire(ClientGameTestContext context, TestSingleplayerContext singleplayer, UUID[] mirrors,
                               IrisHistoryTap.Sample main, Views previous) {
        IrisPipelineCostTap.Counts idleCost = context.computeOnClient(client -> IrisPipelineCostTap.snapshot());
        projection(singleplayer, mirrors, ProjectionMode.OFF);
        awaitClosed(context);
        resetHistory(context);
        long idleStarted = System.nanoTime();
        context.waitFor(client -> System.nanoTime() - idleStarted >= IDLE_NANOS, IDLE_TIMEOUT_TICKS);
        context.waitFor(client -> IrisPipelineCostTap.destroyed(previous.first().pipeline())
            && IrisPipelineCostTap.destroyed(previous.second().pipeline()), TIMEOUT_TICKS);
        context.runOnClient(client -> {
            IrisPipelineCostTap.Counts delta = IrisPipelineCostTap.log("idle-expiry-65s", idleCost);
            assertTrue(!IrisPipelineCostTap.destroyed(main.pipeline()), "idle expiry destroyed the main pipeline");
            assertTrue(!GL11C.glIsTexture(previous.first().mainTexture()) && !GL11C.glIsTexture(previous.first().altTexture())
                && !GL11C.glIsTexture(previous.second().mainTexture()) && !GL11C.glIsTexture(previous.second().altTexture()),
                "released portal pipelines left their actual colortex5 history textures allocated");
            if (!BASELINE) {
                assertTrue(delta.creations() == 0L && delta.destructions() >= 2L,
                    "idle expiry did not release both retained portal pipelines without reconstruction");
                assertTrue(IrisPipelineCostTap.releasedIdleNanos(previous.first().pipeline()) >= MIN_RELEASE_IDLE_NANOS
                    && IrisPipelineCostTap.releasedIdleNanos(previous.second().pipeline()) >= MIN_RELEASE_IDLE_NANOS,
                    "portal pipelines were released before the real 60-second idle retention interval");
            }
            System.out.println("Iris pipeline expiry firstIdleMs="
                + IrisPipelineCostTap.releasedIdleNanos(previous.first().pipeline()) / 1_000_000.0D + " secondIdleMs="
                + IrisPipelineCostTap.releasedIdleNanos(previous.second().pipeline()) / 1_000_000.0D);
        });
        IrisPipelineCostTap.Counts reopenCost = context.computeOnClient(client -> IrisPipelineCostTap.snapshot());
        resetHistory(context);
        projection(singleplayer, mirrors, ProjectionMode.ON);
        awaitViews(context);
        Views reopened = context.computeOnClient(client -> {
            Views next = views(main, "reopen-after-expiry");
            assertTrue(next.first().pipeline() != previous.first().pipeline() && next.second().pipeline() != previous.second().pipeline(),
                "expired portal pipelines were reused after destruction");
            assertTrue(next.firstView() != previous.firstView() && next.secondView() != previous.secondView(),
                "expiry reopen did not create new views for the same physical mirrors");
            IrisPipelineCostTap.Counts delta = IrisPipelineCostTap.log("reopen-after-expiry", reopenCost);
            assertTrue(delta.creations() >= 2L && delta.links() > 0L, "expiry reopen did not construct and link fresh portal pipelines");
            if (!BASELINE) {
                assertTrue(delta.drawCreations() == 0L, "expiry reopen constructed pipelines inside portal draw");
            }
            return next;
        });
        motion(context, main, reopened, "post-expiry-motion");
        context.takeScreenshot("shader-pipeline-reuse-after-expiry");
    }

    private static void motion(ClientGameTestContext context, IrisHistoryTap.Sample main, Views expected, String phase) {
        IrisPipelineCostTap.Counts cost = context.computeOnClient(client -> IrisPipelineCostTap.snapshot());
        for (float yaw : new float[]{175.0F, 185.0F, 180.0F}) {
            context.getInput().lookAt(yaw, 0.0F);
            context.waitTicks(OBSERVATION_TICKS);
            context.runOnClient(client -> stable(expected, views(main, phase), phase));
        }
        context.runOnClient(client -> {
            IrisPipelineCostTap.Counts delta = IrisPipelineCostTap.log(phase, cost);
            assertTrue(delta.creations() == 0L && delta.destructions() == 0L, "camera motion reconstructed or destroyed a pipeline");
        });
    }

    private static Views views(IrisHistoryTap.Sample originalMain, String phase) {
        assertTrue(IrisHistoryTap.failure() == null, IrisHistoryTap.failure());
        IrisHistoryTap.Sample main = IrisHistoryTap.main();
        IrisHistoryTap.Sample first = IrisHistoryTap.view(FIRST);
        IrisHistoryTap.Sample second = IrisHistoryTap.view(SECOND);
        assertTrue(main != null && first != null && second != null, "both portal shader views and the main view must render");
        assertTrue(first.frame() == second.frame() && first.frame() == main.frame(), "the two mirrors must render in the same main frame");
        same(originalMain, main, "main view");
        assertTrue(first.pipeline() != second.pipeline() && first.pipeline() != main.pipeline() && second.pipeline() != main.pipeline(),
            "two same-dimension virtual cameras share a pipeline or reuse the main pipeline");
        assertTrue(first.camera().distanceTo(second.camera()) > 2.0D, "mirrors did not produce distinct virtual cameras");
        isolated(first, second, "two mirrors");
        isolated(first, main, "first mirror and main");
        isolated(second, main, "second mirror and main");
        PortalView firstView = view(FIRST);
        PortalView secondView = view(SECOND);
        assertTrue(firstView != null && secondView != null, "mirror views are missing from the live view set");
        System.out.println("Iris pipeline identity phase=" + phase + " main=" + identity(main) + " first=" + identity(first)
            + " second=" + identity(second) + " frame=" + main.frame());
        return new Views(first, second, firstView, secondView);
    }

    private static String identity(IrisHistoryTap.Sample sample) {
        return Integer.toHexString(System.identityHashCode(sample.pipeline())) + ":colortex5=" + sample.mainTexture() + "/" + sample.altTexture();
    }

    private static void stable(Views previous, Views next, String phase) {
        same(previous.first(), next.first(), phase + " first mirror");
        same(previous.second(), next.second(), phase + " second mirror");
    }

    private static void same(IrisHistoryTap.Sample previous, IrisHistoryTap.Sample next, String label) {
        assertTrue(previous.pipeline() == next.pipeline() && previous.mainTexture() == next.mainTexture()
            && previous.altTexture() == next.altTexture(), label + " replaced its pipeline or actual colortex5 textures");
    }

    private static void isolated(IrisHistoryTap.Sample first, IrisHistoryTap.Sample second, String label) {
        assertTrue(first.mainTexture() > 0 && first.altTexture() > 0 && second.mainTexture() > 0 && second.altTexture() > 0,
            label + " have unallocated colortex5 textures");
        assertTrue(first.mainTexture() != first.altTexture() && second.mainTexture() != second.altTexture()
            && first.mainTexture() != second.mainTexture() && first.mainTexture() != second.altTexture()
            && first.altTexture() != second.mainTexture() && first.altTexture() != second.altTexture(), label + " share actual history textures");
    }

    private static void resetHistory(ClientGameTestContext context) {
        context.runOnClient(client -> IrisHistoryTap.start());
    }

    private static void awaitViews(ClientGameTestContext context) {
        context.waitFor(client -> IrisHistoryTap.failure() != null || enoughFrames(FIRST) && enoughFrames(SECOND), TIMEOUT_TICKS);
        context.runOnClient(client -> assertTrue(IrisHistoryTap.failure() == null, IrisHistoryTap.failure()));
    }

    private static boolean enoughFrames(BlockPos origin) {
        IrisHistoryTap.Sample sample = IrisHistoryTap.view(origin);
        return sample != null && sample.renders() >= MIN_RENDERED_FRAMES;
    }

    private static void awaitClosed(ClientGameTestContext context) {
        context.waitFor(client -> view(FIRST) == null && view(SECOND) == null, TIMEOUT_TICKS);
    }

    private static PortalView view(BlockPos origin) {
        for (PortalView view : WormholesClient.instance().portalViews().current()) {
            if (view.surface().geometry().originX() == origin.getX() && view.surface().geometry().originY() == origin.getY()
                && view.surface().geometry().originZ() == origin.getZ()) {
                return view;
            }
        }
        return null;
    }

    private static void projection(TestSingleplayerContext singleplayer, UUID[] mirrors, ProjectionMode mode) {
        singleplayer.getServer().runOnServer(server -> {
            WormholesModRuntime runtime = runtime(server);
            for (UUID id : mirrors) {
                MinecraftPortal mirror = runtime.portals().get(id);
                assertTrue(mirror != null, "physical mirror disappeared during projection toggling");
                mirror.setProjectionMode(mode);
                runtime.portals().save(mirror);
            }
        });
    }

    private static UUID[] build(MinecraftServer server, ServerPlayer player) {
        ServerLevel level = server.overworld();
        level.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
        level.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
        fill(level, new BlockPos(34, 69, -16), 24, 8, 52, Blocks.AIR.defaultBlockState());
        fill(level, new BlockPos(34, 69, -16), 24, 1, 52, Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState());
        WormholesModRuntime runtime = runtime(server);
        UUID[] mirrors = new UUID[2];
        BlockPos[] origins = {FIRST, SECOND};
        for (int index = 0; index < origins.length; index++) {
            BlockPos origin = origins[index];
            fill(level, origin.offset(-1, 0, -1), 5, 4, 1,
                Blocks.CONCRETE.pick(index == 0 ? DyeColor.YELLOW : DyeColor.BLUE).defaultBlockState());
            MinecraftPortal mirror = runtime.portals().create(player.getUUID(), level, cells(origin), PortalType.PORTAL, new Vec3(0, 0, -1));
            mirror.setMirrorMode(true);
            mirror.setProjectionMode(ProjectionMode.OFF);
            runtime.portals().save(mirror);
            mirrors[index] = mirror.getId();
        }
        return mirrors;
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

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Views(IrisHistoryTap.Sample first, IrisHistoryTap.Sample second, PortalView firstView, PortalView secondView) {
    }
}
