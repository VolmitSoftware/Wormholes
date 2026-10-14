package art.arcane.wormholes.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

public final class CrossingBenchClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(true);
        boolean dedicated = Boolean.getBoolean("wormholes.crossbench.dedicated");
        for (String route : CrossingBench.routes()) {
            if (dedicated) {
                try (TestDedicatedServerContext server = context.worldBuilder().createServer(ClientViewTestConfig.serverProperties());
                     FabricConnection connection = FabricConnection.connect(server)) {
                    CrossingBench.run(new FabricSeamlessClient(context, connection), new FabricSeamlessServer(server, connection),
                        "crossing-bench-" + route + "-dedicated", route);
                }
                continue;
            }
            try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
                FabricConnection connection = FabricConnection.of(singleplayer);
                CrossingBench.run(new FabricSeamlessClient(context, connection),
                    new FabricSeamlessServer(singleplayer.getServer(), connection), "crossing-bench-" + route + "-singleplayer", route);
            }
        }
    }
}
