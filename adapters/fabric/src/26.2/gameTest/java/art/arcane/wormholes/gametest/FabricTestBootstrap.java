package art.arcane.wormholes.gametest;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunctionLoader;
import net.minecraft.resources.ResourceKey;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class FabricTestBootstrap extends TestFunctionLoader implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        TestFunctionLoader.registerLoader(this);
    }

    @Override
    public void load(BiConsumer<ResourceKey<Consumer<GameTestHelper>>, Consumer<GameTestHelper>> register) {
        FabricTestFunctions.load(register);
    }
}
