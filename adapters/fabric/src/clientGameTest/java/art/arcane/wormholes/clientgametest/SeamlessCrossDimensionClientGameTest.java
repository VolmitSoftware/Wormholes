package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.transit.OrientationPolicy;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

public final class SeamlessCrossDimensionClientGameTest implements FabricClientGameTest {
    private static final BlockPos SOURCE_MIN = new BlockPos(0, 70, 20);
    private static final BlockPos DESTINATION_MIN = new BlockPos(0, 70, 220);

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(false);
        try (TestDedicatedServerContext server = context.worldBuilder().createServer(ClientViewTestConfig.serverProperties());
             TestDedicatedServerConnection connection = server.connect()) {
            runPrepared(context, connection, server);
        }
        ClientViewTestConfig.enableSeamless(true);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            runSeamless(context, singleplayer.getConnection(), singleplayer.getServer(), "cross-dimension-singleplayer-frame",
                OrientationPolicy.FRAME);
        }
        try (TestDedicatedServerContext server = context.worldBuilder().createServer(ClientViewTestConfig.serverProperties());
             TestDedicatedServerConnection connection = server.connect()) {
            runSeamless(context, connection, server, "cross-dimension-dedicated-mirror", OrientationPolicy.MIRROR);
        }
    }

    private static void runPrepared(ClientGameTestContext context, TestServerConnection connection, TestServerContext server) {
        connection.waitForChunksDownload();
        context.waitFor(client -> WormholesClient.instance() != null && WormholesClient.instance().session().active(),
            SeamlessScenario.NEGOTIATION_TIMEOUT_TICKS);
        ServerPlayer player = server.computeOnServer(minecraftServer -> connection.getServerPlayer());
        SeamlessScenario.Route route = server.computeOnServer(minecraftServer -> SeamlessScenario.build(player, minecraftServer.overworld(),
            SOURCE_MIN, minecraftServer.getLevel(Level.NETHER), DESTINATION_MIN, OrientationPolicy.FRAME));
        SeamlessScenario.approach(server, player, route);
        SeamlessScenario.awaitPrepared(context, connection);
        SeamlessScenario.Crossing crossing = SeamlessScenario.walkThrough(context, "cross-dimension-prepared");
        SeamlessScenario.assertPreparedTravel(context, crossing, true);
        SeamlessScenario.finish(context, server, player, route);
    }

    private static void runSeamless(ClientGameTestContext context, TestServerConnection connection, TestServerContext server,
                                    String label, OrientationPolicy orientation) {
        connection.waitForChunksDownload();
        context.waitFor(client -> WormholesClient.instance() != null && WormholesClient.instance().session().active(),
            SeamlessScenario.NEGOTIATION_TIMEOUT_TICKS);
        SeamlessScenario.assertSeamlessNegotiated(context);
        ServerPlayer player = server.computeOnServer(minecraftServer -> connection.getServerPlayer());
        SeamlessScenario.Route route = server.computeOnServer(minecraftServer -> SeamlessScenario.build(player, minecraftServer.overworld(),
            SOURCE_MIN, minecraftServer.getLevel(Level.NETHER), DESTINATION_MIN, orientation));
        SeamlessScenario.approach(server, player, route);
        SeamlessScenario.awaitPrepared(context, connection);
        SeamlessScenario.Crossing outbound = SeamlessScenario.walkThrough(context, label);
        SeamlessScenario.assertSeamlessTravel(context, route, outbound);
        SeamlessScenario.turnBack(context, connection);
        SeamlessScenario.Crossing inbound = SeamlessScenario.walkThrough(context, label + "-return");
        SeamlessScenario.assertSeamlessReturn(context, route, inbound);
        SeamlessScenario.finish(context, server, player, route);
    }
}
