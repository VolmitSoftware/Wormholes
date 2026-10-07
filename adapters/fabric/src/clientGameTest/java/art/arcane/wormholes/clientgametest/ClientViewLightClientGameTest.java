package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.portal.PortalType;
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
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.cells;
import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;

public final class ClientViewLightClientGameTest implements FabricClientGameTest {
    private static final int NEGOTIATION_TIMEOUT_TICKS = 400;
    private static final int STREAM_TIMEOUT_TICKS = 600;
    private static final int PARITY_ROUNDS = 40;
    private static final int PARITY_ROUND_TICKS = 5;
    private static final int RELOAD_DISTANCE_BLOCKS = 640;
    private static final int LIGHT_SETTLE_TICKS = 10;
    private static final BlockPos SOURCE_MIN = new BlockPos(0, 70, 20);
    private static final BlockPos DESTINATION_MIN = new BlockPos(0, 70, 220);
    private static final int OFFSET_Z = SOURCE_MIN.getZ() - DESTINATION_MIN.getZ();
    private static final Box ROOM_SHELL = new Box(new BlockPos(-1, 69, 20), 5, 5, 8);
    private static final Box ROOM = new Box(new BlockPos(0, 70, 20), 3, 3, 7);
    private static final Box DESTINATION_SHELL = new Box(new BlockPos(-6, 67, 210), 13, 11, 15);
    private static final Box POCKET = new Box(new BlockPos(0, 70, 214), 3, 3, 8);
    private static final Box CONE = new Box(new BlockPos(-6, 67, 10), 13, 11, 11);
    private static final BlockPos GLOWSTONE = new BlockPos(1, 73, 216);
    private static final BlockPos LIT_CELL = new BlockPos(1, 72, 216);
    private static final BlockPos DARK_CELL = new BlockPos(1, 71, 218);
    private static final BlockPos ROOM_LAMP = new BlockPos(1, 69, 24);
    private static final double STAND_X = 1.5D;
    private static final double STAND_Y = 70.0D;
    private static final double STAND_Z = 25.5D;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enable(true);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            runScenario(context, singleplayer.getConnection(), singleplayer.getServer(), "singleplayer native light");
        }
        ClientViewTestConfig.enable(false);
        try (TestDedicatedServerContext server = context.worldBuilder().createServer(ClientViewTestConfig.serverProperties());
             TestDedicatedServerConnection connection = server.connect()) {
            runScenario(context, connection, server, "dedicated native light with plate fidelity disabled");
        }
    }

    private void runScenario(ClientGameTestContext context, TestServerConnection connection, TestServerContext server, String label) {
        connection.waitForChunksDownload();
        connection.waitForChunksRender();
        context.waitFor(client -> WormholesClient.instance() != null && WormholesClient.instance().session().active(), NEGOTIATION_TIMEOUT_TICKS);
        ServerPlayer player = server.computeOnServer(minecraftServer -> connection.getServerPlayer());
        Scene scene = server.computeOnServer(minecraftServer -> build(minecraftServer, player));
        teleport(server, player, STAND_X, STAND_Y, STAND_Z, 180.0F);
        connection.waitForChunksRender();
        context.waitFor(client -> NativeClientViewAssertions.ready(NativeClientViewAssertions.portalKey(SOURCE_MIN)), STREAM_TIMEOUT_TICKS);
        DestinationLight destination = destinationLight(context, server, connection, label);
        assertPhase(context, server, connection, label + " after the first arrival", destination);
        context.takeScreenshot("clientview-light-" + label.replace(' ', '-') + "-arrival");

        server.runOnServer(minecraftServer -> connection.getServerLevel().setBlockAndUpdate(ROOM_LAMP, Blocks.GLOWSTONE.defaultBlockState()));
        waitForServerBlockLight(context, server, connection, ROOM_LAMP.above(), label);
        context.waitTicks(LIGHT_SETTLE_TICKS);
        assertPhase(context, server, connection, label + " after a real light update", destination);

        teleport(server, player, STAND_X + RELOAD_DISTANCE_BLOCKS, STAND_Y, STAND_Z, 0.0F);
        waitForRoomChunk(context, false);
        teleport(server, player, STAND_X, STAND_Y, STAND_Z, 180.0F);
        waitForRoomChunk(context, true);
        connection.waitForChunksDownload();
        connection.waitForChunksRender();
        context.waitFor(client -> NativeClientViewAssertions.ready(NativeClientViewAssertions.portalKey(SOURCE_MIN)), STREAM_TIMEOUT_TICKS);
        context.waitTicks(LIGHT_SETTLE_TICKS);
        assertPhase(context, server, connection, label + " after a chunk reload", destination);

        server.runOnServer(minecraftServer -> {
            WormholesModRuntime runtime = runtime(minecraftServer);
            runtime.portals().link(player, scene.source(), null);
            runtime.portals().remove(player, scene.source());
            runtime.portals().remove(player, scene.destination());
        });
        context.waitFor(client -> NativeClientViewAssertions.portalKey(SOURCE_MIN) == 0, STREAM_TIMEOUT_TICKS);
        assertLightParity(context, server, connection, ROOM, label + ": real room cells after the portal drop");
        assertLightParity(context, server, connection, CONE, label + ": cone cells after the portal drop");
        context.takeScreenshot("clientview-light-" + label.replace(' ', '-') + "-dropped");
    }

    private static void assertPhase(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, String label,
                                    DestinationLight destination) {
        assertProjectedLight(context, label, destination);
        context.computeOnClient(client -> { NativeClientViewAssertions.assertIsolated(); return true; });
        assertLightParity(context, server, connection, CONE, label + ": physical cells behind the aperture");
        assertLightParity(context, server, connection, ROOM, label + ": real room cells");
    }

    private static DestinationLight destinationLight(ClientGameTestContext context, TestServerContext server, TestServerConnection connection,
                                                     String label) {
        waitForServerBlockLight(context, server, connection, LIT_CELL, label);
        int block = serverBrightness(server, connection, LightLayer.BLOCK, LIT_CELL);
        int sky = serverBrightness(server, connection, LightLayer.SKY, DARK_CELL);
        assertTrue(sky == 0, label + ": the destination pocket is not dark, sky " + sky);
        int realSky = serverBrightness(server, connection, LightLayer.SKY, DARK_CELL.offset(0, 0, OFFSET_Z));
        assertTrue(realSky == 15, label + ": the real cell under the projection is not open sky, sky " + realSky);
        return new DestinationLight(block, sky);
    }

    private static void assertProjectedLight(ClientGameTestContext context, String label, DestinationLight destination) {
        context.waitFor(client -> {
            int key = NativeClientViewAssertions.portalKey(SOURCE_MIN);
            return NativeClientViewAssertions.light(key, LIT_CELL, false) == destination.block()
                && NativeClientViewAssertions.light(key, DARK_CELL, true) == destination.sky();
        }, STREAM_TIMEOUT_TICKS);
    }

    private static void assertLightParity(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, Box box,
                                          String label) {
        List<String> mismatches = List.of();
        for (int round = 0; round < PARITY_ROUNDS; round++) {
            mismatches = lightMismatches(context, server, connection, box);
            if (mismatches.isEmpty()) {
                return;
            }
            context.waitTicks(PARITY_ROUND_TICKS);
        }
        assertTrue(false, label + ": " + mismatches.size() + " cells differ from the server: " + mismatches.subList(0, Math.min(12, mismatches.size())));
    }

    private static List<String> lightMismatches(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, Box box) {
        int cells = box.sizeX() * box.sizeY() * box.sizeZ();
        int[] expectedSky = new int[cells];
        int[] expectedBlock = new int[cells];
        server.runOnServer(minecraftServer -> {
            ServerLevel level = connection.getServerLevel();
            int index = 0;
            for (int x = 0; x < box.sizeX(); x++) {
                for (int y = 0; y < box.sizeY(); y++) {
                    for (int z = 0; z < box.sizeZ(); z++) {
                        BlockPos position = box.min().offset(x, y, z);
                        expectedSky[index] = level.getBrightness(LightLayer.SKY, position);
                        expectedBlock[index] = level.getBrightness(LightLayer.BLOCK, position);
                        index++;
                    }
                }
            }
        });
        return context.computeOnClient(client -> {
            List<String> failures = new ArrayList<>();
            int index = 0;
            for (int x = 0; x < box.sizeX(); x++) {
                for (int y = 0; y < box.sizeY(); y++) {
                    for (int z = 0; z < box.sizeZ(); z++) {
                        BlockPos position = box.min().offset(x, y, z);
                        int sky = client.level.getBrightness(LightLayer.SKY, position);
                        int block = client.level.getBrightness(LightLayer.BLOCK, position);
                        if (sky != expectedSky[index] || block != expectedBlock[index]) {
                            failures.add(position.toShortString() + " sky " + sky + "/" + expectedSky[index] + " block " + block + "/" + expectedBlock[index]);
                        }
                        index++;
                    }
                }
            }
            return failures;
        });
    }

    private static void waitForRoomChunk(ClientGameTestContext context, boolean present) {
        int chunkX = ROOM.min().getX() >> 4;
        int chunkZ = ROOM.min().getZ() >> 4;
        context.waitFor(client -> (client.level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) != null) == present,
            STREAM_TIMEOUT_TICKS);
    }

    private static void waitForServerBlockLight(ClientGameTestContext context, TestServerContext server, TestServerConnection connection,
                                                BlockPos position, String label) {
        for (int waited = 0; waited < STREAM_TIMEOUT_TICKS; waited += PARITY_ROUND_TICKS) {
            if (serverBrightness(server, connection, LightLayer.BLOCK, position) > 0) {
                return;
            }
            context.waitTicks(PARITY_ROUND_TICKS);
        }
        assertTrue(false, label + ": the server never lit " + position.toShortString());
    }

    private static int serverBrightness(TestServerContext server, TestServerConnection connection, LightLayer layer, BlockPos position) {
        return server.computeOnServer(minecraftServer -> connection.getServerLevel().getBrightness(layer, position));
    }

    private static Scene build(MinecraftServer server, ServerPlayer actor) {
        WormholesModRuntime runtime = runtime(server);
        ServerLevel level = server.overworld();
        fill(level, ROOM_SHELL, Blocks.STONE.defaultBlockState());
        fill(level, ROOM, Blocks.AIR.defaultBlockState());
        fill(level, DESTINATION_SHELL, Blocks.STONE.defaultBlockState());
        fill(level, POCKET, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(GLOWSTONE, Blocks.GLOWSTONE.defaultBlockState());
        MinecraftPortal source = runtime.portals().create(actor.getUUID(), level, cells(SOURCE_MIN), PortalType.PORTAL, new Vec3(0, 0, -1));
        MinecraftPortal destination = runtime.portals().create(actor.getUUID(), level, cells(DESTINATION_MIN), PortalType.PORTAL, new Vec3(0, 0, -1));
        assertTrue(runtime.portals().link(actor, source.getId(), destination.getId()), "portal link rejected");
        return new Scene(source.getId(), destination.getId());
    }

    private static void fill(ServerLevel level, Box box, BlockState state) {
        for (int x = 0; x < box.sizeX(); x++) {
            for (int y = 0; y < box.sizeY(); y++) {
                for (int z = 0; z < box.sizeZ(); z++) {
                    level.setBlockAndUpdate(box.min().offset(x, y, z), state);
                }
            }
        }
    }

    private static void teleport(TestServerContext server, ServerPlayer player, double x, double y, double z, float yaw) {
        server.runOnServer(minecraftServer -> player.teleportTo(player.level(), x, y, z, Set.of(), yaw, 0.0F, false));
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Box(BlockPos min, int sizeX, int sizeY, int sizeZ) {
    }

    private record DestinationLight(int block, int sky) {
    }

    private record Scene(UUID source, UUID destination) {
    }
}
