package art.arcane.wormholes.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;
import java.util.function.Function;

public final class GravityPortalAcceptanceClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(true);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            GravityPortalClientGameTest.run(new FabricSeamlessClient(context, singleplayer.getConnection()),
                new Server(singleplayer.getServer(), singleplayer.getConnection()), pressed -> {
                    if (pressed) {
                        context.getInput().holdKey(options -> options.keyJump);
                    } else {
                        context.getInput().releaseKey(options -> options.keyJump);
                    }
                }, context::takeScreenshot);
        }
    }

    private record Server(TestServerContext context, TestServerConnection connection) implements GravityPortalClientGameTest.Server {
        @Override
        public void run(Consumer<MinecraftServer> action) {
            context.runOnServer(action::accept);
        }

        @Override
        public <T> T compute(Function<MinecraftServer, T> action) {
            return context.computeOnServer(action::apply);
        }

        @Override
        public ServerPlayer player() {
            return connection.getServerPlayer();
        }
    }
}
