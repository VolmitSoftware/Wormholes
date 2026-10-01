package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.fabric.WormholesFabric;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.ClientPortal;
import art.arcane.wormholes.modded.client.ClientViewSession;
import art.arcane.wormholes.modded.client.ProjectionOverlay;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.render.ProjectionCellKey;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
        try (TestDedicatedServerContext server = context.worldBuilder().createServer();
             TestDedicatedServerConnection connection = server.connect()) {
            runScenario(context, connection, server, "dedicated");
        }
    }

    private void runScenario(ClientGameTestContext context, TestServerConnection connection, TestServerContext server, String label) {
        connection.waitForChunksDownload();
        connection.waitForChunksRender();
        context.waitFor(client -> WormholesClient.instance() != null, NEGOTIATION_TIMEOUT_TICKS);
        context.waitFor(client -> WormholesClient.instance().session().active(), NEGOTIATION_TIMEOUT_TICKS);
        ServerPlayer player = server.computeOnServer(minecraftServer -> connection.getServerPlayer());
        PortalPair pair = server.computeOnServer(minecraftServer -> buildPair(minecraftServer, player));
        teleport(server, player, SOURCE_MIN.getX() + 1.5D, SOURCE_MIN.getY(), SOURCE_MIN.getZ() + 6.5D, 180.0F);
        connection.waitForChunksRender();
        context.waitFor(client -> attendedCells() > 0, STREAM_TIMEOUT_TICKS);
        long streamedBytes = context.computeOnClient(client -> WormholesClient.instance().stats().markPayloadBytes());
        assertTrue(streamedBytes > 0L, "plate stream delivered no bytes");
        assertApplyParity(context);
        assertCameraLatency(context, connection);
        context.takeScreenshot("clientview-" + label + "-applied");
        assertChunkReloadReapply(context, server, connection);
        assertMemoryBudget(context);
        server.runOnServer(minecraftServer -> {
            WormholesModRuntime runtime = runtime();
            runtime.portals().link(player, pair.source(), null);
            runtime.portals().remove(player, pair.source());
            runtime.portals().remove(player, pair.destination());
        });
        context.waitFor(client -> attendedCells() == 0, STREAM_TIMEOUT_TICKS);
        assertRevertParity(context, server, connection);
        context.takeScreenshot("clientview-" + label + "-reverted");
    }

    private static void assertApplyParity(ClientGameTestContext context) {
        List<String> mismatches = context.computeOnClient(client -> {
            List<String> failures = new ArrayList<>();
            WormholesClient wormholes = WormholesClient.instance();
            ClientViewSession session = wormholes.session();
            ProjectionOverlay overlay = wormholes.tickState().overlay();
            LongArrayList keys = overlay.keys();
            for (int index = 0; index < keys.size(); index++) {
                long key = keys.getLong(index);
                ProjectionOverlay.Entry entry = overlay.get(key);
                ClientPortal portal = session.portal(entry.portalKey());
                if (portal == null || portal.plate() == null) {
                    failures.add("overlay cell without a portal at " + key);
                    continue;
                }
                BlockPos position = new BlockPos(ProjectionCellKey.unpackX(key), ProjectionCellKey.unpackY(key), ProjectionCellKey.unpackZ(key));
                BlockState shown = client.level.getBlockState(position);
                if (shown != entry.projected()) {
                    failures.add(position + " shows " + shown + " instead of " + entry.projected());
                }
                if (!portal.sweep().applied(position.getX(), position.getY(), position.getZ())) {
                    failures.add(position + " is applied but outside the swept cone");
                }
            }
            return failures;
        });
        assertTrue(mismatches.isEmpty(), "apply parity failed: " + mismatches);
    }

    private static void assertRevertParity(ClientGameTestContext context, TestServerContext server, TestServerConnection connection) {
        int overlayCells = context.computeOnClient(client -> WormholesClient.instance().tickState().overlay().size());
        assertTrue(overlayCells == 0, "overlay still holds " + overlayCells + " cells after the portal drop");
        BlockState clientMarker = context.computeOnClient(client -> client.level.getBlockState(MARKER));
        BlockState serverMarker = server.computeOnServer(minecraftServer -> connection.getServerLevel().getBlockState(MARKER));
        boolean markerLoaded = context.computeOnClient(client -> client.level.getChunkSource().hasChunk(MARKER.getX() >> 4, MARKER.getZ() >> 4));
        assertTrue(!markerLoaded || clientMarker == serverMarker, "client shows " + clientMarker + " where the server has " + serverMarker);
        List<BlockPos> positions = new ArrayList<>(REVERT_BOX_CELLS);
        for (int x = -4; x <= 6; x++) {
            for (int y = 66; y <= 76; y++) {
                for (int z = 10; z <= 20; z++) {
                    positions.add(new BlockPos(x, y, z));
                }
            }
        }
        List<BlockState> shown = context.computeOnClient(client -> states(client.level, positions));
        List<BlockState> real = server.computeOnServer(minecraftServer -> states(connection.getServerLevel(), positions));
        for (int index = 0; index < positions.size(); index++) {
            assertTrue(shown.get(index) == real.get(index), positions.get(index) + " shows " + shown.get(index) + " after revert, server has " + real.get(index));
        }
    }

    private static List<BlockState> states(Level level, List<BlockPos> positions) {
        List<BlockState> states = new ArrayList<>(positions.size());
        for (BlockPos position : positions) {
            states.add(level.getBlockState(position));
        }
        return states;
    }

    private static void assertCameraLatency(ClientGameTestContext context, TestServerConnection connection) {
        int before = context.computeOnClient(client -> attendedCells());
        context.getInput().lookAt(SOURCE_MIN.offset(1, 1, 0));
        context.waitTick();
        int after = context.computeOnClient(client -> attendedCells());
        int sweepMicros = context.computeOnClient(client -> WormholesClient.instance().stats().sweepMicrosP50());
        assertTrue(before >= 0 && after >= 0, "cone counters went negative");
        assertTrue(sweepMicros >= 0, "sweep telemetry missing");
        connection.waitForClientboundPackets();
    }

    private static void assertChunkReloadReapply(ClientGameTestContext context, TestServerContext server, TestServerConnection connection) {
        int appliedBefore = context.computeOnClient(client -> attendedCells());
        context.computeOnClient(client -> WormholesClient.instance().stats().markPayloadBytes());
        ServerPlayer player = server.computeOnServer(minecraftServer -> connection.getServerPlayer());
        teleport(server, player, SOURCE_MIN.getX() + RELOAD_DISTANCE_BLOCKS, SOURCE_MIN.getY(), SOURCE_MIN.getZ(), 0.0F);
        connection.waitForChunksDownload();
        teleport(server, player, SOURCE_MIN.getX() + 1.5D, SOURCE_MIN.getY(), SOURCE_MIN.getZ() + 6.5D, 180.0F);
        connection.waitForChunksDownload();
        connection.waitForChunksRender();
        context.waitFor(client -> attendedCells() >= appliedBefore, STREAM_TIMEOUT_TICKS);
        long reloadBytes = context.computeOnClient(client -> WormholesClient.instance().stats().markPayloadBytes());
        int resets = context.computeOnClient(client -> WormholesClient.instance().session().resets());
        assertTrue(resets == 0 || reloadBytes == 0L, "chunk reload cycle re-streamed " + reloadBytes + " bytes");
        assertApplyParity(context);
        assertProjectedBlockEntities(context);
        assertBlockEntitiesSurviveAChunkResend(context, server, connection);
    }

    private static void assertBlockEntitiesSurviveAChunkResend(ClientGameTestContext context, TestServerContext server,
                                                                TestServerConnection connection) {
        BlockPos projected = context.computeOnClient(client -> firstProjectedBlockEntity());
        assertTrue(projected != null, "no projected block entity to resend");
        ClientboundLevelChunkWithLightPacket packet = server.computeOnServer(minecraftServer -> {
            ServerLevel level = connection.getServerLevel();
            return new ClientboundLevelChunkWithLightPacket(level.getChunk(projected.getX() >> 4, projected.getZ() >> 4), level.getLightEngine(),
                null, null);
        });
        List<String> failures = context.computeOnClient(client -> {
            client.getConnection().handleLevelChunkWithLight(packet);
            return projectedBlockEntityFailures(client);
        });
        assertTrue(failures.isEmpty(), "projected block entities right after a chunk packet: " + failures);
        assertApplyParity(context);
    }

    private static BlockPos firstProjectedBlockEntity() {
        ProjectionOverlay overlay = WormholesClient.instance().tickState().overlay();
        LongArrayList keys = overlay.keys();
        for (int index = 0; index < keys.size(); index++) {
            long key = keys.getLong(index);
            ProjectionOverlay.Entry entry = overlay.get(key);
            if (!entry.pending() && entry.projected().hasBlockEntity()) {
                return new BlockPos(ProjectionCellKey.unpackX(key), ProjectionCellKey.unpackY(key), ProjectionCellKey.unpackZ(key));
            }
        }
        return null;
    }

    private static void assertProjectedBlockEntities(ClientGameTestContext context) {
        List<String> failures = context.computeOnClient(ClientViewClientGameTest::projectedBlockEntityFailures);
        assertTrue(failures.isEmpty(), "projected block entities after the chunk reload: " + failures);
    }

    private static List<String> projectedBlockEntityFailures(Minecraft client) {
        List<String> missing = new ArrayList<>();
        ProjectionOverlay overlay = WormholesClient.instance().tickState().overlay();
        LongArrayList keys = overlay.keys();
        int checked = 0;
        for (int index = 0; index < keys.size(); index++) {
            long key = keys.getLong(index);
            ProjectionOverlay.Entry entry = overlay.get(key);
            if (entry.pending() || !entry.projected().hasBlockEntity()) {
                continue;
            }
            checked++;
            BlockPos position = new BlockPos(ProjectionCellKey.unpackX(key), ProjectionCellKey.unpackY(key), ProjectionCellKey.unpackZ(key));
            BlockEntity entity = client.level.getChunkAt(position).getBlockEntities().get(position);
            if (entity == null || !entity.getType().isValid(entry.projected())) {
                missing.add(position + " shows " + entry.projected() + " without its block entity");
            }
        }
        if (checked == 0) {
            missing.add("no projected block entity in the cone");
        }
        return missing;
    }

    private static void assertMemoryBudget(ClientGameTestContext context) {
        long bytes = context.computeOnClient(client -> WormholesClient.instance().session().plates().bytes());
        long budget = context.computeOnClient(client -> WormholesClient.instance().config().plateMemoryBytes());
        assertTrue(bytes <= budget, "plate store holds " + bytes + " bytes over the " + budget + " budget");
    }

    private static int attendedCells() {
        WormholesClient wormholes = WormholesClient.instance();
        if (wormholes == null || wormholes.tickState().overlay() == null) {
            return 0;
        }
        return wormholes.tickState().overlay().size();
    }

    private static PortalPair buildPair(MinecraftServer server, ServerPlayer actor) {
        WormholesModRuntime runtime = runtime();
        ServerLevel level = server.overworld();
        fill(level, DESTINATION_MIN.offset(-6, -2, -10), 13, 8, 10, Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(MARKER, Blocks.GOLD_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(DESTINATION_CHEST, Blocks.CHEST.defaultBlockState());
        MinecraftPortal source = runtime.portals().create(actor.getUUID(), level, cells(SOURCE_MIN), PortalType.PORTAL, new Vec3(0, 0, -1));
        MinecraftPortal destination = runtime.portals().create(actor.getUUID(), level, cells(DESTINATION_MIN), PortalType.PORTAL, new Vec3(0, 0, -1));
        assertTrue(runtime.portals().link(actor, source.getId(), destination.getId()), "portal link rejected");
        return new PortalPair(source.getId(), destination.getId());
    }

    private static List<BlockPos> cells(BlockPos min) {
        List<BlockPos> cells = new ArrayList<>(9);
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
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

    private static void teleport(TestServerContext server, ServerPlayer player, double x, double y, double z, float yaw) {
        server.runOnServer(minecraftServer -> player.teleportTo(player.level(), x, y, z, Set.of(), yaw, 0.0F, false));
    }

    private static WormholesModRuntime runtime() {
        for (ModInitializer initializer : FabricLoader.getInstance().getEntrypoints("main", ModInitializer.class)) {
            if (initializer instanceof WormholesFabric fabric) {
                try {
                    Field field = WormholesFabric.class.getDeclaredField("runtime");
                    field.setAccessible(true);
                    return (WormholesModRuntime) field.get(fabric);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Wormholes runtime is not reachable", failure);
                }
            }
        }
        throw new IllegalStateException("Wormholes main entrypoint is not loaded");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record PortalPair(UUID source, UUID destination) {
    }
}
