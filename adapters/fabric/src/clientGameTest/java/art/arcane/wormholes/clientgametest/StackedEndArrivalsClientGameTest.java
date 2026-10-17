package art.arcane.wormholes.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

public final class StackedEndArrivalsClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(true);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
            EndPortalFloor.stacked(new FabricSeamlessClient(context, FabricConnection.of(singleplayer)),
                new FabricSeamlessServer(singleplayer.getServer(), FabricConnection.of(singleplayer)), "stacked-end-arrivals");
        }
    }
}
