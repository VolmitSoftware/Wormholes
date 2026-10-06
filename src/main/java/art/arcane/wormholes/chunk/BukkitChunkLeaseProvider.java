package art.arcane.wormholes.chunk;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import art.arcane.optics.plate.ChunkLeaseRegistry;
import art.arcane.optics.spi.OpticsScheduler;

public final class BukkitChunkLeaseProvider {
    private static final AtomicReference<ChunkLeaseRegistry<World>> REGISTRY = new AtomicReference<>();

    private BukkitChunkLeaseProvider() {
    }

    public static void install(Plugin plugin, OpticsScheduler<?, World> scheduler) {
        install(new ChunkLeaseRegistry<World>(new BukkitChunkLeasePlatform(plugin), scheduler, new ChunkLeaseRegistry.Options(0L, 250L, 3)));
    }

    public static void install(ChunkLeaseRegistry<World> registry) {
        ChunkLeaseRegistry<World> installed = Objects.requireNonNull(registry);
        while (true) {
            ChunkLeaseRegistry<World> current = REGISTRY.get();
            if (current == installed) {
                return;
            }
            if (current != null) {
                throw new IllegalStateException("Bukkit chunk lease registry is already installed");
            }
            if (REGISTRY.compareAndSet(null, installed)) {
                return;
            }
        }
    }

    public static ChunkLeaseRegistry<World> registry() {
        ChunkLeaseRegistry<World> registry = REGISTRY.get();
        if (registry == null) {
            throw new IllegalStateException("Bukkit chunk lease registry is not installed");
        }
        return registry;
    }

    public static void worldUnloaded(UUID worldId) {
        ChunkLeaseRegistry<World> registry = REGISTRY.get();
        if (registry != null) {
            registry.worldUnloaded(Objects.requireNonNull(worldId));
        }
    }

    public static void shutdown() {
        ChunkLeaseRegistry<World> registry = REGISTRY.getAndSet(null);
        if (registry != null) {
            registry.shutdown();
        }
    }
}
