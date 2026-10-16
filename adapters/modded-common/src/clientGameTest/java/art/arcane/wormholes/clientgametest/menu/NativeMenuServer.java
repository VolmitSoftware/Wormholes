package art.arcane.wormholes.clientgametest.menu;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;

public interface NativeMenuServer {
    void run(BiConsumer<MinecraftServer, ServerPlayer> action);

    <T> T compute(BiFunction<MinecraftServer, ServerPlayer, T> function);
}
