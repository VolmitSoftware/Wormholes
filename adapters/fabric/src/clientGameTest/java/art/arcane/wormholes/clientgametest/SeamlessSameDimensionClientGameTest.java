package art.arcane.wormholes.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

public final class SeamlessSameDimensionClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(false);
        try (TestDedicatedServerContext server = context.worldBuilder().createServer(ClientViewTestConfig.serverProperties());
             TestDedicatedServerConnection connection = server.connect()) {
            SeamlessSameDimension.prepared(new FabricSeamlessClient(context, connection), new FabricSeamlessServer(server, connection),
                "same-dimension-prepared");
        }
        ClientViewTestConfig.enableSeamless(true);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            SeamlessSameDimension.seamless(new FabricSeamlessClient(context, singleplayer.getConnection()),
                new FabricSeamlessServer(singleplayer.getServer(), singleplayer.getConnection()), "same-dimension-singleplayer");
        }
        try (TestDedicatedServerContext server = context.worldBuilder().createServer(ClientViewTestConfig.serverProperties());
             TestDedicatedServerConnection connection = server.connect()) {
            SeamlessSameDimension.seamless(new FabricSeamlessClient(context, connection), new FabricSeamlessServer(server, connection),
                "same-dimension-dedicated");
        }
    }
}
