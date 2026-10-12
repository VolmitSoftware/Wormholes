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

public final class GravityMovementAcceptanceClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(true);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            GravityMovementClientGameTest.run(new FabricSeamlessClient(context, singleplayer.getConnection()),
                new Server(singleplayer.getServer(), singleplayer.getConnection()), controls -> input(context, controls));
        }
    }

    private static void input(ClientGameTestContext context, GravityMovementClientGameTest.Controls controls) {
        if (controls.forward()) {
            context.getInput().holdKey(options -> options.keyUp);
        } else {
            context.getInput().releaseKey(options -> options.keyUp);
        }
        if (controls.right()) {
            context.getInput().holdKey(options -> options.keyRight);
        } else {
            context.getInput().releaseKey(options -> options.keyRight);
        }
        if (controls.jump()) {
            context.getInput().holdKey(options -> options.keyJump);
        } else {
            context.getInput().releaseKey(options -> options.keyJump);
        }
        if (controls.descend()) {
            context.getInput().holdKey(options -> options.keyShift);
        } else {
            context.getInput().releaseKey(options -> options.keyShift);
        }
        if (controls.sprint()) {
            context.getInput().holdKey(options -> options.keySprint);
        } else {
            context.getInput().releaseKey(options -> options.keySprint);
        }
    }

    private record Server(TestServerContext context, TestServerConnection connection) implements GravityMovementClientGameTest.Server {
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
