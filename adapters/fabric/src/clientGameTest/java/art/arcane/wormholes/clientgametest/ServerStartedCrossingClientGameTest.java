package art.arcane.wormholes.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

public final class ServerStartedCrossingClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(true);
        AssertionError singleplayerFailure = null;
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ServerStartedCrossing.run(new FabricSeamlessClient(context, FabricConnection.of(singleplayer)),
                new FabricSeamlessServer(singleplayer.getServer(), FabricConnection.of(singleplayer)), "server-started-crossing-singleplayer");
        } catch (AssertionError failure) {
            singleplayerFailure = failure;
        }
        try (TestDedicatedServerContext server = context.worldBuilder().createServer(ClientViewTestConfig.serverProperties());
             FabricConnection connection = FabricConnection.connect(server)) {
            ServerStartedCrossing.run(new FabricSeamlessClient(context, connection), new FabricSeamlessServer(server, connection),
                "server-started-crossing-dedicated");
        } catch (AssertionError failure) {
            if (singleplayerFailure != null) {
                failure.addSuppressed(singleplayerFailure);
            }
            throw failure;
        }
        if (singleplayerFailure != null) {
            throw singleplayerFailure;
        }
    }
}
