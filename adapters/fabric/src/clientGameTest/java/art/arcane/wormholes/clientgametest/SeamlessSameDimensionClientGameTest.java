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

public final class SeamlessSameDimensionClientGameTest implements FabricClientGameTest {
    private static final BlockPos FAR_SOURCE = new BlockPos(0, 70, 20);
    private static final BlockPos FAR_DESTINATION = new BlockPos(0, 70, 420);
    private static final BlockPos NEAR_SOURCE = new BlockPos(48, 70, 20);
    private static final BlockPos NEAR_DESTINATION = new BlockPos(48, 70, 44);
    private static final BlockPos TURNING_SOURCE = new BlockPos(-48, 70, 20);
    private static final BlockPos TURNING_DESTINATION = new BlockPos(-48, 70, 420);

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(false);
        try (TestDedicatedServerContext server = context.worldBuilder().createServer(ClientViewTestConfig.serverProperties());
             TestDedicatedServerConnection connection = server.connect()) {
            runPrepared(context, connection, server);
        }
        ClientViewTestConfig.enableSeamless(true);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            runSeamless(context, singleplayer.getConnection(), singleplayer.getServer(), "same-dimension-singleplayer");
        }
        try (TestDedicatedServerContext server = context.worldBuilder().createServer(ClientViewTestConfig.serverProperties());
             TestDedicatedServerConnection connection = server.connect()) {
            runSeamless(context, connection, server, "same-dimension-dedicated");
        }
    }

    private static void runPrepared(ClientGameTestContext context, TestServerConnection connection, TestServerContext server) {
        ServerPlayer player = join(context, connection, server);
        SeamlessScenario.Route route = server.computeOnServer(minecraftServer -> SeamlessScenario.build(player, minecraftServer.overworld(),
            FAR_SOURCE, minecraftServer.overworld(), FAR_DESTINATION, OrientationPolicy.FRAME));
        SeamlessScenario.approach(server, player, route);
        SeamlessScenario.awaitPrepared(context, connection);
        SeamlessScenario.Crossing crossing = SeamlessScenario.walkThrough(context, "same-dimension-prepared");
        SeamlessScenario.assertPreparedTravel(context, crossing, false);
        SeamlessScenario.finish(context, server, player, route);
    }

    private static void runSeamless(ClientGameTestContext context, TestServerConnection connection, TestServerContext server, String label) {
        ServerPlayer player = join(context, connection, server);
        SeamlessScenario.assertSeamlessNegotiated(context);
        cross(context, connection, server, player, new Pass(FAR_SOURCE, FAR_DESTINATION, label + "-far", false));
        cross(context, connection, server, player, new Pass(NEAR_SOURCE, NEAR_DESTINATION, label + "-near", false));
        cross(context, connection, server, player, new Pass(TURNING_SOURCE, TURNING_DESTINATION, label + "-turning", true));
    }

    private static void cross(ClientGameTestContext context, TestServerConnection connection, TestServerContext server, ServerPlayer player,
                              Pass pass) {
        SeamlessScenario.Route route = server.computeOnServer(minecraftServer -> SeamlessScenario.build(player, minecraftServer.overworld(),
            pass.source(), minecraftServer.overworld(), pass.destination(), OrientationPolicy.FRAME));
        SeamlessScenario.approach(server, player, route);
        SeamlessScenario.awaitPrepared(context, connection);
        SeamlessScenario.Crossing crossing = pass.turning() ? SeamlessScenario.walkThroughTurning(context, pass.label())
            : SeamlessScenario.walkThrough(context, pass.label());
        SeamlessScenario.assertSeamlessTravel(context, route, crossing);
        SeamlessScenario.finish(context, server, player, route);
    }

    private static ServerPlayer join(ClientGameTestContext context, TestServerConnection connection, TestServerContext server) {
        connection.waitForChunksDownload();
        context.waitFor(client -> WormholesClient.instance() != null && WormholesClient.instance().session().active(),
            SeamlessScenario.NEGOTIATION_TIMEOUT_TICKS);
        return server.computeOnServer(minecraftServer -> connection.getServerPlayer());
    }

    private record Pass(BlockPos source, BlockPos destination, String label, boolean turning) {
    }
}
