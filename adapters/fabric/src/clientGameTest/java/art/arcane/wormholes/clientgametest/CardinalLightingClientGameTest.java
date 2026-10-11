package art.arcane.wormholes.clientgametest;

import com.mojang.logging.LogUtils;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

public final class CardinalLightingClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getConnection().waitForChunksRender();
            context.runOnClient(minecraft -> CardinalLightingAssertions.verify());
            context.waitTicks(10);
            LogUtils.getLogger().info("WORMHOLES_CARDINAL_LIGHTING_PASS actual_gpu_directions nested_restore stable_reuse inventory_isolation external_shader_isolation independent_instances owned_buffer_cleanup");
        }
    }
}
