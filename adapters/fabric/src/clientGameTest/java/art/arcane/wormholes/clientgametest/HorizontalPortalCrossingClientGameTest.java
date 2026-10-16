package art.arcane.wormholes.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

public final class HorizontalPortalCrossingClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(true);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            HorizontalPortalCrossing.run(new FabricSeamlessClient(context, FabricConnection.of(singleplayer)),
                new FabricSeamlessServer(singleplayer.getServer(), FabricConnection.of(singleplayer)), "horizontal-portal-crossing-singleplayer");
        }
        try (TestDedicatedServerContext server = context.worldBuilder().createServer(ClientViewTestConfig.serverProperties());
             FabricConnection connection = FabricConnection.connect(server)) {
            HorizontalPortalCrossing.run(new FabricSeamlessClient(context, connection), new FabricSeamlessServer(server, connection),
                "horizontal-portal-crossing-dedicated");
        }
    }
}
