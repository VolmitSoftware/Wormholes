package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;
import static art.arcane.wormholes.clientgametest.SeamlessScenario.assertTrue;

public final class PortalHorizonClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final int TIMEOUT_TICKS = 600;
    private static final int SETTLE_TICKS = 20;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(true);
        run(context, 0, 100);
        run(context, 100, 0);
    }

    private static void run(ClientGameTestContext context, int sourceHeight, int destinationHeight) {
        try (TestSingleplayerContext world = context.worldBuilder().adjustSettings(PortalHorizonClientGameTest::horizonWorld).create()) {
            SeamlessClient client = new FabricSeamlessClient(context, world.getConnection());
            SeamlessScenario.join(client);
            double horizon = context.computeOnClient(minecraft -> minecraft.level.getLevelData().getHorizonHeight(minecraft.level));
            assertTrue(4.62D < horizon && horizon < 104.62D, "horizon fixture must place its cameras on opposite sides of the actual horizon: " + horizon);
            Scene scene = world.getServer().computeOnServer(server -> build(server, world.getConnection().getServerPlayer(), sourceHeight, destinationHeight));
            boolean hidden = context.computeOnClient(minecraft -> minecraft.gui.hud.isHidden());
            try {
                context.runOnClient(minecraft -> {
                    if (!minecraft.gui.hud.isHidden()) {
                        minecraft.gui.hud.toggle();
                    }
                });
                context.waitFor(minecraft -> minecraft.player.position().distanceTo(scene.sourceFeet()) < 0.05D, TIMEOUT_TICKS);
                context.getInput().lookAt(180.0F, 0.0F);
                world.getConnection().waitForChunksRender();
                context.waitFor(minecraft -> WormholesClient.instance().seamlessTravel().armed(scene.source()), TIMEOUT_TICKS);
                context.waitTicks(SETTLE_TICKS);
                FramebufferProbe through = capture(context);
                context.takeScreenshot("portal-horizon-through-" + sourceHeight + "-to-" + destinationHeight);
                world.getServer().runOnServer(server -> {
                    runtime(server).portals().get(scene.source()).setProjectionMode(ProjectionMode.OFF);
                    runtime(server).portals().get(scene.destination()).setProjectionMode(ProjectionMode.OFF);
                    runtime(server).portals().save(runtime(server).portals().get(scene.source()));
                    runtime(server).portals().save(runtime(server).portals().get(scene.destination()));
                    park(world.getConnection().getServerPlayer(), scene.destinationFeet());
                });
                context.waitFor(minecraft -> minecraft.player.position().distanceTo(scene.destinationFeet()) < 0.05D
                    && WormholesClient.instance().portalViews().current().isEmpty(), TIMEOUT_TICKS);
                context.getInput().lookAt(180.0F, 0.0F);
                world.getConnection().waitForChunksRender();
                context.waitTicks(SETTLE_TICKS);
                FramebufferProbe direct = capture(context);
                context.takeScreenshot("portal-horizon-direct-" + sourceHeight + "-to-" + destinationHeight);
                assertTrue(through.width() == direct.width() && through.height() == direct.height(), "horizon captures changed framebuffer dimensions");
                int width = through.width();
                int height = through.height();
                int radius = width / 30;
                int top = height / 2 + height / 90;
                int bottom = height / 2 + height / 12;
                double difference = difference(through, direct, width / 2 - radius, top, radius * 2, bottom - top);
                assertTrue(difference < 8.0D, "portal horizon differs from its actual destination camera: " + difference
                    + " for heights " + sourceHeight + " -> " + destinationHeight);
                LOGGER.info("WORMHOLES_HORIZON_PASS source_height {} destination_height {} horizon_pixel_difference {}", sourceHeight, destinationHeight, difference);
            } finally {
                context.runOnClient(minecraft -> {
                    if (minecraft.gui.hud.isHidden() != hidden) {
                        minecraft.gui.hud.toggle();
                    }
                });
                world.getServer().runOnServer(server -> {
                    ServerPlayer player = world.getConnection().getServerPlayer();
                    runtime(server).portals().remove(player, scene.source());
                    runtime(server).portals().remove(player, scene.destination());
                });
            }
        }
    }

    private static void horizonWorld(WorldCreationUiState settings) {
        settings.setWorldType(new WorldCreationUiState.WorldTypeEntry(settings.getSettings().worldgenLoadContext()
            .lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.DEBUG)));
    }

    private static Scene build(MinecraftServer server, ServerPlayer player, int sourceHeight, int destinationHeight) {
        ServerLevel level = server.overworld();
        level.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
        level.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
        BlockPos sourceMin = new BlockPos(304, sourceHeight, 304);
        BlockPos destinationMin = new BlockPos(336, destinationHeight, 304);
        platform(level, sourceMin);
        platform(level, destinationMin);
        MinecraftPortal source = runtime(server).portals().create(player.getUUID(), level, cells(sourceMin), PortalType.PORTAL, new Vec3(0, 0, -1));
        MinecraftPortal destination = runtime(server).portals().create(player.getUUID(), level, cells(destinationMin), PortalType.PORTAL, new Vec3(0, 0, -1));
        assertTrue(source != null && destination != null, "horizon portals could not be created");
        assertTrue(runtime(server).portals().link(player, source.getId(), destination.getId()), "horizon outgoing link rejected");
        assertTrue(runtime(server).portals().link(player, destination.getId(), source.getId()), "horizon return link rejected");
        Vec3 sourceFeet = new Vec3(sourceMin.getX() + 4.5D, sourceHeight + 3.0D, sourceMin.getZ() + 3.5D);
        Vec3 destinationFeet = new Vec3(destinationMin.getX() + 4.5D, destinationHeight + 3.0D, destinationMin.getZ() + 3.5D);
        player.setGameMode(GameType.CREATIVE);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        park(player, sourceFeet);
        return new Scene(source.getId(), destination.getId(), sourceFeet, destinationFeet);
    }

    private static void park(ServerPlayer player, Vec3 feet) {
        player.teleportTo(player.level(), feet.x, feet.y, feet.z, Set.of(), 180.0F, 0.0F, false);
        player.setDeltaMovement(Vec3.ZERO);
    }

    private static List<BlockPos> cells(BlockPos min) {
        List<BlockPos> cells = new ArrayList<>(81);
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 9; y++) {
                cells.add(min.offset(x, y, 0));
            }
        }
        return cells;
    }

    private static void platform(ServerLevel level, BlockPos min) {
        for (int x = -6; x <= 14; x++) {
            for (int z = -6; z <= 8; z++) {
                for (int y = 0; y <= 12; y++) {
                    level.setBlockAndUpdate(min.offset(x, y, z), Blocks.AIR.defaultBlockState());
                }
                level.setBlockAndUpdate(min.offset(x, 2, z), Blocks.STONE.defaultBlockState());
            }
        }
    }

    private static FramebufferProbe capture(ClientGameTestContext context) {
        AtomicReference<FramebufferProbe> captured = new AtomicReference<>();
        context.runOnClient(minecraft -> FramebufferProbe.capture(minecraft, captured::set));
        context.waitFor(minecraft -> captured.get() != null, TIMEOUT_TICKS);
        return captured.get();
    }

    private static double difference(FramebufferProbe first, FramebufferProbe second, int x, int y, int width, int height) {
        long total = 0L;
        for (int row = y; row < y + height; row++) {
            for (int column = x; column < x + width; column++) {
                int a = first.read(column, row);
                int b = second.read(column, row);
                for (int shift = 0; shift <= 16; shift += 8) {
                    total += Math.abs(((a >> shift) & 255) - ((b >> shift) & 255));
                }
            }
        }
        return total / (width * height * 3.0D);
    }

    private record Scene(UUID source, UUID destination, Vec3 sourceFeet, Vec3 destinationFeet) {
    }
}
