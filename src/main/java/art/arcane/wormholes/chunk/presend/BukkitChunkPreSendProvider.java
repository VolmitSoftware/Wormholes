package art.arcane.wormholes.chunk.presend;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class BukkitChunkPreSendProvider {
    private static final AtomicReference<ChunkPreSendService<World, Player>> SERVICE = new AtomicReference<>();

    private BukkitChunkPreSendProvider() {
    }

    public static ChunkPreSendService<World, Player> install(Plugin plugin) {
        return install(new ChunkPreSendService<>(new BukkitChunkPreSendPlatform(Objects.requireNonNull(plugin))));
    }

    public static ChunkPreSendService<World, Player> install(ChunkPreSendService<World, Player> service) {
        ChunkPreSendService<World, Player> installed = Objects.requireNonNull(service);
        while (true) {
            ChunkPreSendService<World, Player> current = SERVICE.get();
            if (current == installed) {
                return installed;
            }
            if (current != null) {
                throw new IllegalStateException("Bukkit chunk pre-send service is already installed");
            }
            if (SERVICE.compareAndSet(null, installed)) {
                return installed;
            }
        }
    }

    public static ChunkPreSendService<World, Player> service() {
        ChunkPreSendService<World, Player> service = SERVICE.get();
        if (service == null) {
            throw new IllegalStateException("Bukkit chunk pre-send service is not installed");
        }
        return service;
    }

    public static boolean installed() {
        return SERVICE.get() != null;
    }

    public static void shutdown() {
        SERVICE.set(null);
        ChunkPreSendSettings.reset();
    }
}
