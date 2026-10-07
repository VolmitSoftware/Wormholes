package art.arcane.wormholes.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

public final class SeamlessWalkThroughClientGameTest implements FabricClientGameTest {
    private static final int TRIPS = 2;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(true);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            SeamlessWalkThrough.netherPortal(new FabricSeamlessClient(context, singleplayer.getConnection()),
                new FabricSeamlessServer(singleplayer.getServer(), singleplayer.getConnection()), "walk-nether-portal", TRIPS);
        }
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            SeamlessWalkThrough.framePortal(new FabricSeamlessClient(context, singleplayer.getConnection()),
                new FabricSeamlessServer(singleplayer.getServer(), singleplayer.getConnection()), "walk-frame-portal", TRIPS);
        }
    }
}
