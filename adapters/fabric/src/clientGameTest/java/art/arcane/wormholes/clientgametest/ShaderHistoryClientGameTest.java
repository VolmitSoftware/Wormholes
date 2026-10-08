package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.WormholesClient;
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

import java.util.Set;
import java.util.UUID;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.cells;
import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;

public final class ShaderHistoryClientGameTest implements FabricClientGameTest {
    private static final int TIMEOUT_TICKS = 600;
    private static final int OBSERVATION_TICKS = 30;
    private static final int MIN_RENDERED_FRAMES = 8;
    private static final BlockPos FIRST = new BlockPos(40, 70, 20);
    private static final BlockPos SECOND = new BlockPos(46, 70, 23);
    private static final Vec3 POSITION = new Vec3(44.5D, 70.0D, 30.5D);

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enable();
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getConnection().waitForChunksDownload();
            context.waitFor(client -> WormholesClient.instance() != null && WormholesClient.instance().session().active(), TIMEOUT_TICKS);
            context.runOnClient(client -> {
                assertTrue(Iris.isPackInUseQuick() && Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline,
                    "shader history acceptance requires an active Iris shader pipeline");
                System.out.println("Shader history acceptance pack: " + Iris.getCurrentPackName());
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
                context.runOnClient(client -> IrisHistoryTap.start());
                context.waitFor(client -> IrisHistoryTap.failure() != null
                    || IrisHistoryTap.main() != null && IrisHistoryTap.main().renders() >= MIN_RENDERED_FRAMES,
                    TIMEOUT_TICKS);
                context.runOnClient(client -> assertTrue(IrisHistoryTap.failure() == null, IrisHistoryTap.failure()));
                IrisHistoryTap.Sample originalMain = context.computeOnClient(client -> IrisHistoryTap.main());
                singleplayer.getServer().runOnServer(server -> {
                    WormholesModRuntime runtime = runtime(server);
                    for (UUID id : mirrors) {
                        MinecraftPortal mirror = runtime.portals().get(id);
                        mirror.setProjectionMode(ProjectionMode.ON);
                        runtime.portals().save(mirror);
                    }
                });
                context.waitFor(client -> IrisHistoryTap.failure() != null || enoughFrames(FIRST) && enoughFrames(SECOND), TIMEOUT_TICKS);
                context.runOnClient(client -> verify(originalMain));
                context.takeScreenshot("shader-history-two-views");
                for (float yaw : new float[]{175.0F, 185.0F, 180.0F}) {
                    context.getInput().lookAt(yaw, 0.0F);
                    context.waitTicks(OBSERVATION_TICKS);
                    context.runOnClient(client -> verify(originalMain));
                }
                context.takeScreenshot("shader-history-stable-after-motion");
            } finally {
                context.runOnClient(client -> IrisHistoryTap.stop());
                singleplayer.getServer().runOnServer(server -> {
                    WormholesModRuntime runtime = runtime(server);
                    for (UUID id : mirrors) {
                        runtime.portals().remove(player, id);
                    }
                });
            }
        }
    }

    private static void verify(IrisHistoryTap.Sample originalMain) {
        assertTrue(IrisHistoryTap.failure() == null, IrisHistoryTap.failure());
        IrisHistoryTap.Sample main = IrisHistoryTap.main();
        IrisHistoryTap.Sample first = IrisHistoryTap.view(FIRST);
        IrisHistoryTap.Sample second = IrisHistoryTap.view(SECOND);
        assertTrue(main != null && first != null && second != null, "both portal shader views and the main view must render");
        assertTrue(first.frame() == second.frame() && first.frame() == main.frame(),
            "both portal shader views must render in the same main frame");
        assertTrue(main.pipeline() == originalMain.pipeline(), "portal rendering replaced the main shader pipeline");
        assertTrue(first.pipeline() != second.pipeline() && first.pipeline() != main.pipeline() && second.pipeline() != main.pipeline(),
            "two same-dimension virtual cameras share an Iris rendering pipeline or reuse the main pipeline");
        assertTrue(first.camera().distanceTo(second.camera()) > 2.0D, "mirror fixture did not produce distinct virtual cameras");
        isolated(first, second, "the two portal views");
        isolated(first, main, "the first portal and the main view");
        isolated(second, main, "the second portal and the main view");
    }

    private static void isolated(IrisHistoryTap.Sample first, IrisHistoryTap.Sample second, String label) {
        assertTrue(first.mainTexture() > 0 && first.altTexture() > 0 && second.mainTexture() > 0 && second.altTexture() > 0,
            label + " have unallocated colortex5 history textures");
        assertTrue(first.mainTexture() != second.mainTexture() && first.mainTexture() != second.altTexture()
            && first.altTexture() != second.mainTexture() && first.altTexture() != second.altTexture(),
            label + " share actual colortex5 history textures");
    }

    private static boolean enoughFrames(BlockPos origin) {
        IrisHistoryTap.Sample sample = IrisHistoryTap.view(origin);
        return sample != null && sample.renders() >= MIN_RENDERED_FRAMES;
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
}
