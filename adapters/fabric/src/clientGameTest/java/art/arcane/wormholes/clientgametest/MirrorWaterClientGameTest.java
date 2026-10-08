package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
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
import java.util.concurrent.atomic.AtomicReference;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.cells;
import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;

public final class MirrorWaterClientGameTest implements FabricClientGameTest {
    private static final int TIMEOUT_TICKS = 600;
    private static final int SETTLE_TICKS = 20;
    private static final int PHASE_FRAMES = 6;
    private static final int TOGGLE_ROUNDS = 3;
    private static final BlockPos MIRROR_MIN = new BlockPos(40, 70, 20);
    private static final BlockPos POND_MIN = new BlockPos(44, 69, -12);
    private static final Vec3 POND_CENTER = new Vec3(46.5D, 69.88D, 24.5D);
    private static final Vec3 CAMERA_POSITION = new Vec3(43.0D, 70.0D, 30.5D);

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enable();
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getConnection().waitForChunksDownload();
            context.waitFor(client -> WormholesClient.instance() != null && WormholesClient.instance().session().active(), TIMEOUT_TICKS);
            TestServerContext server = singleplayer.getServer();
            ServerPlayer player = server.computeOnServer(minecraftServer -> singleplayer.getConnection().getServerPlayer());
            UUID mirror = server.computeOnServer(minecraftServer -> build(minecraftServer, player));
            boolean hidden = context.computeOnClient(client -> client.gui.hud.isHidden());
            try {
                server.runOnServer(minecraftServer -> {
                    player.setGameMode(GameType.CREATIVE);
                    player.getAbilities().flying = false;
                    player.onUpdateAbilities();
                    player.teleportTo(player.level(), CAMERA_POSITION.x, CAMERA_POSITION.y, CAMERA_POSITION.z, Set.of(), 180.0F, 15.0F, false);
                    player.setDeltaMovement(Vec3.ZERO);
                });
                context.waitFor(client -> client.player.position().distanceTo(CAMERA_POSITION) < 0.05D, TIMEOUT_TICKS);
                context.getInput().lookAt(180.0F, 15.0F);
                context.runOnClient(client -> {
                    if (!client.gui.hud.isHidden()) {
                        client.gui.hud.toggle();
                    }
                });
                singleplayer.getConnection().waitForChunksRender();
                context.waitTicks(SETTLE_TICKS);
                Crop crop = context.computeOnClient(client -> {
                    int[] center = FramebufferProbe.screen(client, POND_CENTER);
                    int[] aperture = FramebufferProbe.screen(client, new Vec3(MIRROR_MIN.getX() + 1.5D, MIRROR_MIN.getY() + 1.5D, MIRROR_MIN.getZ() + 0.5D));
                    assertTrue(center != null && aperture != null, "pond and mirror must both be visible");
                    int radius = Math.max(6, client.gameRenderer.mainRenderTarget().width / 80);
                    assertTrue(Math.abs(center[0] - aperture[0]) > radius * 2, "the pond crop must lie outside the mirror aperture");
                    return new Crop(center[0] - radius, center[1] - radius, radius * 2 + 1);
                });
                int[] dry = capture(context, crop);
                context.takeScreenshot("mirror-water-dry-calibration");
                server.runOnServer(minecraftServer -> fill(minecraftServer.overworld(), POND_MIN, 48, 1, 48, Blocks.WATER.defaultBlockState()));
                singleplayer.getConnection().waitForChunksRender();
                context.waitTicks(SETTLE_TICKS);
                int[] wet = capture(context, crop);
                double contrast = difference(dry, wet);
                assertTrue(contrast >= 12.0D, "the pond crop does not distinguish visible water from its floor: " + contrast);
                context.takeScreenshot("mirror-water-off-baseline");
                double animation = 0.0D;
                for (int frame = 0; frame < PHASE_FRAMES; frame++) {
                    context.waitTicks(2);
                    animation = Math.max(animation, difference(wet, capture(context, crop)));
                }
                double tolerance = Math.max(8.0D, animation * 3.0D + 3.0D);
                assertTrue(tolerance < contrast * 0.6D, "water animation exceeds the disappearance detection margin: " + tolerance + "/" + contrast);
                for (int round = 0; round < TOGGLE_ROUNDS; round++) {
                    context.getInput().lookAt(170.0F + round * 10.0F, 15.0F);
                    context.waitTicks(SETTLE_TICKS);
                    crop = context.computeOnClient(client -> {
                        int[] center = FramebufferProbe.screen(client, POND_CENTER);
                        assertTrue(center != null, "water crop left the moving camera view");
                        int radius = Math.max(6, client.gameRenderer.mainRenderTarget().width / 80);
                        return new Crop(center[0] - radius, center[1] - radius, radius * 2 + 1);
                    });
                    wet = capture(context, crop);
                    projection(context, server, mirror, ProjectionMode.ON);
                    context.takeScreenshot("mirror-water-on-" + round);
                    assertWater(context, crop, wet, dry, tolerance, "ON round " + round);
                    projection(context, server, mirror, ProjectionMode.OFF);
                    context.takeScreenshot("mirror-water-off-" + round);
                    assertWater(context, crop, wet, dry, tolerance, "OFF round " + round);
                }
            } finally {
                context.runOnClient(client -> {
                    if (client.gui.hud.isHidden() != hidden) {
                        client.gui.hud.toggle();
                    }
                });
                server.runOnServer(minecraftServer -> runtime(minecraftServer).portals().remove(player, mirror));
            }
        }
    }

    private static UUID build(MinecraftServer server, ServerPlayer player) {
        ServerLevel level = server.overworld();
        level.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
        level.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
        fill(level, new BlockPos(35, 69, 15), 20, 10, 20, Blocks.AIR.defaultBlockState());
        fill(level, new BlockPos(35, 69, 15), 20, 1, 20, Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState());
        fill(level, POND_MIN.below(), 48, 1, 48, Blocks.SEA_LANTERN.defaultBlockState());
        fill(level, POND_MIN, 48, 1, 48, Blocks.AIR.defaultBlockState());
        fill(level, MIRROR_MIN.offset(-1, 0, -1), 5, 4, 1, Blocks.CONCRETE.pick(DyeColor.YELLOW).defaultBlockState());
        WormholesModRuntime runtime = runtime(server);
        MinecraftPortal mirror = runtime.portals().create(player.getUUID(), level, cells(MIRROR_MIN), PortalType.PORTAL, new Vec3(0, 0, -1));
        mirror.setMirrorMode(true);
        mirror.setProjectionMode(ProjectionMode.OFF);
        runtime.portals().save(mirror);
        return mirror.getId();
    }

    private static void projection(ClientGameTestContext context, TestServerContext server, UUID id, ProjectionMode mode) {
        server.runOnServer(minecraftServer -> {
            WormholesModRuntime runtime = runtime(minecraftServer);
            MinecraftPortal mirror = runtime.portals().get(id);
            mirror.setProjectionMode(mode);
            runtime.portals().save(mirror);
        });
        context.waitFor(client -> mode == ProjectionMode.ON
            ? NativeClientViewAssertions.ready(NativeClientViewAssertions.portalKey(MIRROR_MIN))
            : NativeClientViewAssertions.portalKey(MIRROR_MIN) == 0, TIMEOUT_TICKS);
        context.waitTicks(SETTLE_TICKS);
    }

    private static void assertWater(ClientGameTestContext context, Crop crop, int[] wet, int[] dry, double tolerance, String phase) {
        for (int frame = 0; frame < PHASE_FRAMES; frame++) {
            int[] actual = capture(context, crop);
            double wetDistance = difference(wet, actual);
            double dryDistance = difference(dry, actual);
            if (wetDistance > tolerance || wetDistance >= dryDistance) {
                context.takeScreenshot("mirror-water-failed");
            }
            assertTrue(wetDistance <= tolerance && wetDistance < dryDistance,
                phase + " frame " + frame + ": main-view water crop differs from baseline by " + wetDistance
                    + " (limit " + tolerance + ", dry floor distance " + dryDistance + ", crop " + crop + ")");
            context.waitTicks(2);
        }
    }

    private static int[] capture(ClientGameTestContext context, Crop crop) {
        AtomicReference<FramebufferProbe> captured = new AtomicReference<>();
        context.runOnClient(client -> FramebufferProbe.capture(client, captured::set));
        context.waitFor(client -> captured.get() != null, TIMEOUT_TICKS);
        FramebufferProbe probe = captured.get();
        assertTrue(crop.x() >= 0 && crop.y() >= 0 && crop.x() + crop.size() <= probe.width()
            && crop.y() + crop.size() <= probe.height(), "water crop extends beyond the framebuffer: " + crop);
        int[] pixels = new int[crop.size() * crop.size()];
        for (int y = 0; y < crop.size(); y++) {
            for (int x = 0; x < crop.size(); x++) {
                pixels[y * crop.size() + x] = probe.read(crop.x() + x, crop.y() + y);
            }
        }
        return pixels;
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
}
