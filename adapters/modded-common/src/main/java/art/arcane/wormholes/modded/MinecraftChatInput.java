package art.arcane.wormholes.modded;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class MinecraftChatInput implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final Map<MinecraftServer, MinecraftChatInput> SERVICES = new ConcurrentHashMap<>();

    private final WormholesModRuntime runtime;
    private final Map<UUID, Consumer<String>> inputs = new HashMap<>();
    private MinecraftServer server;

    public MinecraftChatInput(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public static boolean chat(ServerPlayer player, String message) {
        MinecraftChatInput service = SERVICES.get(player.level().getServer());
        return service != null && service.accept(player, message);
    }

    public void start() {
        runtime.requireServerThread();
        server = runtime.server();
        SERVICES.put(server, this);
    }

    public void await(ServerPlayer player, Consumer<String> callback) {
        if (player == null || callback == null) {
            return;
        }
        inputs.put(player.getUUID(), callback);
    }

    public boolean accept(ServerPlayer player, String message) {
        UUID id = player.getUUID();
        Consumer<String> callback = inputs.remove(id);
        if (callback == null) {
            return false;
        }
        if (!runtime.schedule(() -> callback.accept(message), 1L)) {
            inputs.putIfAbsent(id, callback);
            LOGGER.warn("Could not deliver chat input from {}; the prompt remains open for another attempt.", player.getPlainTextName());
        }
        return true;
    }

    public void disconnected(ServerPlayer player) {
        inputs.remove(player.getUUID());
    }

    @Override
    public void close() {
        if (server != null) {
            SERVICES.remove(server, this);
        }
        inputs.clear();
        server = null;
    }
}
