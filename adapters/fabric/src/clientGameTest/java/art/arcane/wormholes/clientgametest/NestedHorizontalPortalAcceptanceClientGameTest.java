package art.arcane.wormholes.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.level.ServerPlayer;

public final class NestedHorizontalPortalAcceptanceClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(true);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
            FabricSeamlessClient client = new FabricSeamlessClient(context, singleplayer.getConnection());
            SeamlessScenario.join(client);
            ServerPlayer player = singleplayer.getServer().computeOnServer(server -> singleplayer.getConnection().getServerPlayer());
            NestedHorizontalPortalClientGameTest.Scene scene = singleplayer.getServer()
                .computeOnServer(server -> NestedHorizontalPortalClientGameTest.build(server, player));
            try {
                NestedHorizontalPortalClientGameTest.run(client, scene, viewpoint -> singleplayer.getServer()
                    .runOnServer(server -> NestedHorizontalPortalClientGameTest.place(server, player, viewpoint)));
            } finally {
                singleplayer.getServer().runOnServer(server -> NestedHorizontalPortalClientGameTest.remove(server, player, scene));
            }
        }
    }
}
