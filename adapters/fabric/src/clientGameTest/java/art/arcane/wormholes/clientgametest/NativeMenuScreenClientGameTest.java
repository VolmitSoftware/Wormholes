package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.clientgametest.menu.MenuScreenDriver;
import art.arcane.wormholes.clientgametest.menu.NativeMenuScreenScenario;
import art.arcane.wormholes.clientgametest.menu.NativeMenuShots;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.nio.file.Path;

public final class NativeMenuScreenClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            FabricConnection.of(singleplayer).waitForChunksRender();
            Path directory = context.computeOnClient(minecraft -> minecraft.gameDirectory.toPath().resolve("screenshots").resolve("native-menus"));
            MenuScreenDriver driver = new MenuScreenDriver(new FabricNativeMenuInput(context));
            NativeMenuScreenScenario.run(driver, new NativeMenuShots(driver, directory));
        }
    }
}
