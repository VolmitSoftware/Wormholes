package art.arcane.wormholes.platform;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import art.arcane.optics.plate.PlateWorkers;
import art.arcane.optics.spi.OpticsScheduler;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.render.FidelitySettings;

public final class BukkitOpticsScheduler implements OpticsScheduler<Player, World> {
    private static final long MILLIS_PER_TICK = 50L;
    private static final long OWNER_DELAY_TICKS = 1L;
    private static final AtomicReference<BukkitOpticsScheduler> ACTIVE = new AtomicReference<BukkitOpticsScheduler>();

    private final Plugin plugin;
    private final PlateWorkers compute;
    private final Operations operations;
    private final AtomicLong ticks;

    public BukkitOpticsScheduler(Plugin plugin, PlateWorkers compute) {
        this(plugin, compute, new FoliaOperations());
    }

    BukkitOpticsScheduler(Plugin plugin, PlateWorkers compute, Operations operations) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.compute = Objects.requireNonNull(compute, "compute");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.ticks = new AtomicLong();
    }

    public static BukkitOpticsScheduler install(Plugin plugin) {
        BukkitOpticsScheduler installed = new BukkitOpticsScheduler(plugin, new PlateWorkers("Wormholes-Plate-", FidelitySettings.plateWorkers));
        if (!ACTIVE.compareAndSet(null, installed)) {
            installed.compute.shutdown();
            throw new IllegalStateException("Bukkit optics scheduler is already installed");
        }
        return installed;
    }

    public static BukkitOpticsScheduler active() {
        return ACTIVE.get();
    }

    public static void shutdown() {
        BukkitOpticsScheduler scheduler = ACTIVE.getAndSet(null);
        if (scheduler != null) {
            scheduler.compute.shutdown();
        }
    }

    public void advanceTick() {
        ticks.incrementAndGet();
    }

    @Override
    public boolean runForObserver(Player observer, Runnable task) {
        return operations.runEntity(plugin, observer, task, OWNER_DELAY_TICKS);
    }

    @Override
    public boolean runForRegion(World world, int chunkX, int chunkZ, Runnable task) {
        return operations.runRegion(plugin, world, chunkX, chunkZ, task, OWNER_DELAY_TICKS);
    }

    @Override
    public PlateWorkers compute() {
        return compute;
    }

    @Override
    public boolean schedule(Runnable task, long delayMillis) {
        try {
            return operations.runAsync(plugin, Objects.requireNonNull(task), delayTicks(delayMillis));
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.SEVERE, "Optics task scheduling failed", error);
            return false;
        }
    }

    @Override
    public long tick() {
        return ticks.get();
    }

    private static long delayTicks(long delayMillis) {
        long normalized = Math.max(0L, delayMillis);
        long ticks = normalized / MILLIS_PER_TICK;
        return normalized % MILLIS_PER_TICK == 0L ? ticks : ticks + 1L;
    }

    interface Operations {
        boolean runEntity(Plugin plugin, Entity entity, Runnable task, long delayTicks);

        boolean runRegion(Plugin plugin, World world, int chunkX, int chunkZ, Runnable task, long delayTicks);

        boolean runAsync(Plugin plugin, Runnable task, long delayTicks);
    }

    private static final class FoliaOperations implements Operations {
        @Override
        public boolean runEntity(Plugin plugin, Entity entity, Runnable task, long delayTicks) {
            return FoliaScheduler.runEntity(plugin, entity, task, delayTicks);
        }

        @Override
        public boolean runRegion(Plugin plugin, World world, int chunkX, int chunkZ, Runnable task, long delayTicks) {
            return FoliaScheduler.runRegion(plugin, world, chunkX, chunkZ, task, delayTicks);
        }

        @Override
        public boolean runAsync(Plugin plugin, Runnable task, long delayTicks) {
            return FoliaScheduler.runAsync(plugin, task, delayTicks);
        }
    }
}
