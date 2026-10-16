package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.clientgametest.menu.MenuScreenDriver;
import art.arcane.wormholes.clientgametest.menu.NativeMenuPortalScenario;
import art.arcane.wormholes.clientgametest.menu.NativeMenuShots;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;

import java.nio.file.Path;

public final class NativeMenuPortalClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestDedicatedServerContext server = context.worldBuilder().createServer(ClientViewTestConfig.serverProperties());
             FabricConnection connection = FabricConnection.connect(server)) {
            connection.waitForChunksRender();
            Path directory = context.computeOnClient(minecraft -> minecraft.gameDirectory.toPath().resolve("screenshots").resolve("native-menus"));
            MenuScreenDriver driver = new MenuScreenDriver(new FabricNativeMenuInput(context));
            NativeMenuPortalScenario.run(driver, new NativeMenuShots(driver, directory), new FabricNativeMenuServer(server, connection));
        }
    }
}
