package art.arcane.wormholes.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;

public final class VerticalCameraLoopAcceptanceClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(true);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            boolean gravityFlip = singleplayer.getServer().computeOnServer(server -> runtime(server).configuration().settings().getTransit()
                .gravityFlipEnabled);
            try {
                VerticalCameraLoopClientGameTest.run(new FabricSeamlessClient(context, singleplayer.getConnection()),
                    new FabricSeamlessServer(singleplayer.getServer(), singleplayer.getConnection()), "vertical-camera-loop-singleplayer",
                    enabled -> singleplayer.getServer().runOnServer(server -> runtime(server).configuration().settings().getTransit()
                        .gravityFlipEnabled = enabled));
            } finally {
                singleplayer.getServer().runOnServer(server -> runtime(server).configuration().settings().getTransit().gravityFlipEnabled = gravityFlip);
            }
        }
    }
}
