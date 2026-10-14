package art.arcane.wormholes.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

public final class SeamlessWalkThroughClientGameTest implements FabricClientGameTest {
    private static final int TRIPS = 2;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(true);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
            SeamlessWalkThrough.netherPortal(new FabricSeamlessClient(context, FabricConnection.of(singleplayer)),
                new FabricSeamlessServer(singleplayer.getServer(), FabricConnection.of(singleplayer)), "walk-nether-portal", TRIPS);
        }
        try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
            SeamlessWalkThrough.framePortal(new FabricSeamlessClient(context, FabricConnection.of(singleplayer)),
                new FabricSeamlessServer(singleplayer.getServer(), FabricConnection.of(singleplayer)), "walk-frame-portal", TRIPS);
        }
    }
}
