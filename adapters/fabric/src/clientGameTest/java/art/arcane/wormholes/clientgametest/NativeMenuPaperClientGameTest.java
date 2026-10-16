package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.clientgametest.menu.MenuScreenDriver;
import art.arcane.wormholes.clientgametest.menu.NativeMenuShots;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.nio.file.Path;

public final class NativeMenuPaperClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        Path directory = context.computeOnClient(minecraft -> minecraft.gameDirectory.toPath().resolve("screenshots").resolve("native-menus"));
        MenuScreenDriver driver = new MenuScreenDriver(new FabricNativeMenuInput(context));
        NativeMenuPaperScenario.run(new FabricTestClient(context), driver, new NativeMenuShots(driver, directory), "native-menu-paper");
    }
}
