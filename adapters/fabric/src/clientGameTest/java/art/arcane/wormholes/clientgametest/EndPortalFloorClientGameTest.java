package art.arcane.wormholes.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

public final class EndPortalFloorClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(true);
        AssertionError failure = null;
        for (EndPortalFloor.Travel travel : EndPortalFloor.Travel.values()) {
            try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
                EndPortalFloor.run(new FabricSeamlessClient(context, FabricConnection.of(singleplayer)),
                    new FabricSeamlessServer(singleplayer.getServer(), FabricConnection.of(singleplayer)),
                    travel == EndPortalFloor.Travel.TWO_WAY ? "end-portal-floor" : "end-portal-floor-one-way", travel);
            } catch (AssertionError error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
