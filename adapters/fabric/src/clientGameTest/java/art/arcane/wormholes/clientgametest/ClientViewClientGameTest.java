package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.ClientMeshSections;
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
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.cells;
import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;

public final class ClientViewClientGameTest implements FabricClientGameTest {
    private static final int NEGOTIATION_TIMEOUT_TICKS = 400;
    private static final int STREAM_TIMEOUT_TICKS = 600;
    private static final int RELOAD_DISTANCE_BLOCKS = 640;
    private static final BlockPos SOURCE_MIN = new BlockPos(0, 70, 20);
    private static final BlockPos DESTINATION_MIN = new BlockPos(0, 70, 220);
    private static final BlockPos MARKER = new BlockPos(1, 70, 212);
    private static final BlockPos DESTINATION_CHEST = new BlockPos(1, 71, 219);
    private static final int REVERT_BOX_CELLS = 11 * 11 * 11;

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
        PortalPair pair = server.computeOnServer(minecraftServer -> buildPair(minecraftServer, player));
        teleport(server, player, 1.5D, 70.0D, 26.5D, 180.0F);
        connection.waitForChunksRender();
        awaitContent(context);
        context.getInput().lookAt(SOURCE_MIN.offset(1, 1, 0));
        context.waitTick();
        awaitContent(context);
        connection.waitForClientboundPackets();
        assertPhysicalParity(context, server, connection);
        context.takeScreenshot("clientview-" + label + "-native");
        teleport(server, player, SOURCE_MIN.getX() + RELOAD_DISTANCE_BLOCKS, 70.0D, 26.5D, 0.0F);
        connection.waitForChunksDownload();
        teleport(server, player, 1.5D, 70.0D, 26.5D, 180.0F);
        connection.waitForChunksDownload();
        connection.waitForChunksRender();
        awaitContent(context);
        server.runOnServer(minecraftServer -> {
            ServerLevel level = connection.getServerLevel();
            player.connection.send(new ClientboundLevelChunkWithLightPacket(level.getChunkAt(SOURCE_MIN), level.getLightEngine(), null, null));
        });
        connection.waitForClientboundPackets();
        awaitContent(context);
        assertPhysicalParity(context, server, connection);
        boolean withinBudget = context.computeOnClient(client -> WormholesClient.instance().session().meshes().bytes()
            + WormholesClient.instance().session().plates().bytes() <= WormholesClient.instance().config().plateMemoryBytes());
        assertTrue(withinBudget, "native resident content exceeded the configured memory budget");
        int key = context.computeOnClient(client -> NativeClientViewAssertions.portalKey(SOURCE_MIN));
        server.runOnServer(minecraftServer -> {
            runtime().portals().remove(player, pair.source());
            runtime().portals().remove(player, pair.destination());
        });
        context.waitFor(client -> !WormholesClient.instance().session().portals().containsKey(key)
            && NativeClientViewAssertions.sections(key) == 0, STREAM_TIMEOUT_TICKS);
        assertPhysicalParity(context, server, connection);
    }

    private static void awaitContent(ClientGameTestContext context) {
        try {
            context.waitFor(client -> {
                int key = NativeClientViewAssertions.portalKey(SOURCE_MIN);
                if (!NativeClientViewAssertions.ready(key) || NativeClientViewAssertions.state(key, MARKER) != Blocks.GOLD_BLOCK.defaultBlockState()) {
                    return false;
                }
                ClientMeshSections.Section section = NativeClientViewAssertions.section(key, DESTINATION_CHEST);
                return section != null && section.state(NativeClientViewAssertions.cell(key, DESTINATION_CHEST)).is(Blocks.CHEST)
                    && section.blockEntity(NativeClientViewAssertions.cell(key, DESTINATION_CHEST)) != null;
            }, STREAM_TIMEOUT_TICKS);
        } catch (AssertionError failure) {
            String diagnostic = context.computeOnClient(client -> NativeClientViewAssertions.describe(SOURCE_MIN, List.of(MARKER, DESTINATION_CHEST)));
            throw new AssertionError("Native section content did not become ready: " + diagnostic, failure);
        }
        context.computeOnClient(client -> { NativeClientViewAssertions.assertIsolated(); return true; });
    }

    private static void assertPhysicalParity(ClientGameTestContext context, TestServerContext server, TestServerConnection connection) {
        List<BlockPos> positions = new ArrayList<>();
        for (int x = -4; x <= 6; x++) {
            for (int y = 66; y <= 76; y++) {
                for (int z = 10; z <= 29; z++) {
                    positions.add(new BlockPos(x, y, z));
                }
            }
        }
        List<BlockState> shown = context.computeOnClient(client -> states(client.level, positions));
        List<BlockState> real = server.computeOnServer(minecraftServer -> states(connection.getServerLevel(), positions));
        for (int index = 0; index < positions.size(); index++) {
            assertTrue(shown.get(index) == real.get(index), "native scene changed physical block " + positions.get(index));
        }
    }

    private static List<BlockState> states(Level level, List<BlockPos> positions) {
        List<BlockState> result = new ArrayList<>(positions.size());
        for (BlockPos position : positions) {
            result.add(level.getBlockState(position));
        }
        return result;
    }

    private static PortalPair buildPair(MinecraftServer server, ServerPlayer actor) {
        WormholesModRuntime runtime = runtime();
        ServerLevel level = server.overworld();
        fill(level, SOURCE_MIN.offset(-6, -1, 1), 13, 1, 10, Blocks.STONE.defaultBlockState());
        fill(level, DESTINATION_MIN.offset(-6, -2, -10), 13, 8, 10, Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(MARKER, Blocks.GOLD_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(DESTINATION_CHEST, Blocks.CHEST.defaultBlockState());
        MinecraftPortal source = runtime.portals().create(actor.getUUID(), level, cells(SOURCE_MIN), PortalType.PORTAL, new Vec3(0, 0, -1));
        MinecraftPortal destination = runtime.portals().create(actor.getUUID(), level, cells(DESTINATION_MIN), PortalType.PORTAL, new Vec3(0, 0, -1));
        assertTrue(runtime.portals().link(actor, source.getId(), destination.getId()), "portal link rejected");
        return new PortalPair(source.getId(), destination.getId());
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

    private static void teleport(TestServerContext server, ServerPlayer player, double x, double y, double z, float yaw) {
        server.runOnServer(minecraftServer -> player.teleportTo(player.level(), x, y, z, Set.of(), yaw, 0.0F, false));
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record PortalPair(UUID source, UUID destination) {
    }
}
