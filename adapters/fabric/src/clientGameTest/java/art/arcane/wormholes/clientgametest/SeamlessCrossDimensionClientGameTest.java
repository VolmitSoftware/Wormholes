package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.transit.OrientationPolicy;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

public final class SeamlessCrossDimensionClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(true);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            SeamlessCrossDimension.seamless(new FabricSeamlessClient(context, FabricConnection.of(singleplayer)),
                new FabricSeamlessServer(singleplayer.getServer(), FabricConnection.of(singleplayer)), "cross-dimension-singleplayer-frame",
                OrientationPolicy.FRAME);
        }
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            SeamlessCrossDimension.stress(new FabricSeamlessClient(context, FabricConnection.of(singleplayer)),
                new FabricSeamlessServer(singleplayer.getServer(), FabricConnection.of(singleplayer)), "cross-dimension-stress-singleplayer");
        }
        try (TestDedicatedServerContext server = context.worldBuilder().createServer(ClientViewTestConfig.serverProperties());
             FabricConnection connection = FabricConnection.connect(server)) {
            SeamlessCrossDimension.seamless(new FabricSeamlessClient(context, connection), new FabricSeamlessServer(server, connection),
                "cross-dimension-dedicated-mirror", OrientationPolicy.MIRROR);
        }
        try (TestDedicatedServerContext server = context.worldBuilder().createServer(ClientViewTestConfig.serverProperties());
             FabricConnection connection = FabricConnection.connect(server)) {
            SeamlessCrossDimension.stress(new FabricSeamlessClient(context, connection), new FabricSeamlessServer(server, connection),
                "cross-dimension-stress-dedicated");
        }
    }
}
