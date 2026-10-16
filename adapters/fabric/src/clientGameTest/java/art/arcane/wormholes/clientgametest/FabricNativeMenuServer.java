package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.clientgametest.menu.NativeMenuServer;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;

final class FabricNativeMenuServer implements NativeMenuServer {
    private final TestServerContext server;
    private final FabricConnection connection;

    FabricNativeMenuServer(TestServerContext server, FabricConnection connection) {
        this.server = server;
        this.connection = connection;
    }

    @Override
    public void run(BiConsumer<MinecraftServer, ServerPlayer> action) {
        server.runOnServer(minecraftServer -> action.accept(minecraftServer, connection.getServerPlayer()));
    }

    @Override
    public <T> T compute(BiFunction<MinecraftServer, ServerPlayer, T> function) {
        return server.computeOnServer(minecraftServer -> function.apply(minecraftServer, connection.getServerPlayer()));
    }
}
