package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.transit.OrientationPolicy;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

public final class SeamlessCrossDimensionClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(false);
        try (TestDedicatedServerContext server = context.worldBuilder().createServer(ClientViewTestConfig.serverProperties());
             TestDedicatedServerConnection connection = server.connect()) {
            SeamlessCrossDimension.prepared(new FabricSeamlessClient(context, connection), new FabricSeamlessServer(server, connection),
                "cross-dimension-prepared");
        }
        ClientViewTestConfig.enableSeamless(true);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            SeamlessCrossDimension.seamless(new FabricSeamlessClient(context, singleplayer.getConnection()),
                new FabricSeamlessServer(singleplayer.getServer(), singleplayer.getConnection()), "cross-dimension-singleplayer-frame",
                OrientationPolicy.FRAME);
        }
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            SeamlessCrossDimension.stress(new FabricSeamlessClient(context, singleplayer.getConnection()),
                new FabricSeamlessServer(singleplayer.getServer(), singleplayer.getConnection()), "cross-dimension-stress-singleplayer");
        }
        try (TestDedicatedServerContext server = context.worldBuilder().createServer(ClientViewTestConfig.serverProperties());
             TestDedicatedServerConnection connection = server.connect()) {
            SeamlessCrossDimension.seamless(new FabricSeamlessClient(context, connection), new FabricSeamlessServer(server, connection),
                "cross-dimension-dedicated-mirror", OrientationPolicy.MIRROR);
        }
        try (TestDedicatedServerContext server = context.worldBuilder().createServer(ClientViewTestConfig.serverProperties());
             TestDedicatedServerConnection connection = server.connect()) {
            SeamlessCrossDimension.stress(new FabricSeamlessClient(context, connection), new FabricSeamlessServer(server, connection),
                "cross-dimension-stress-dedicated");
        }
    }
}
