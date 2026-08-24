package art.arcane.wormholes.platform;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;

public final class BukkitRegionTaskProvider {
    private static final AtomicReference<Service> SERVICE = new AtomicReference<Service>();

    private BukkitRegionTaskProvider() {
    }

    public static void install(Plugin plugin) {
        Service installed = new Service(Objects.requireNonNull(plugin, "plugin"));
        if (!SERVICE.compareAndSet(null, installed)) {
            throw new IllegalStateException("Bukkit region task provider is already installed");
        }
    }

    public static boolean run(
        World world,
        int chunkX,
        int chunkZ,
        Runnable task,
        Runnable retired,
        long delayTicks
    ) {
        Service service = SERVICE.get();
        if (service == null) {
            return false;
        }
        return service.run(world, chunkX, chunkZ, task, retired, delayTicks);
    }

    public static void worldUnloaded(UUID worldId) {
        Service service = SERVICE.get();
        if (service != null) {
            service.retireWorld(Objects.requireNonNull(worldId, "worldId"));
        }
    }

    public static void worldLoaded(UUID worldId) {
        Service service = SERVICE.get();
        if (service != null) {
            service.activateWorld(Objects.requireNonNull(worldId, "worldId"));
        }
    }

    public static void shutdown() {
        Service service = SERVICE.getAndSet(null);
        if (service != null) {
            service.shutdown();
        }
    }

    @FunctionalInterface
    interface RegionScheduler {
        boolean schedule(
            Plugin plugin,
            World world,
            int chunkX,
            int chunkZ,
            Runnable task,
            long delayTicks
        );
    }

    static final class Service {
        private final Plugin plugin;
        private final RegionScheduler scheduler;
        private final AtomicLong sequence;
        private final ConcurrentHashMap<Long, Pending> pending;
        private final Set<UUID> unavailableWorlds;
        private final AtomicBoolean closed;
        private final ReentrantReadWriteLock lifecycleLock;
        private final Lock lifecycleReadLock;
        private final Lock lifecycleWriteLock;

        Service(Plugin plugin) {
            this(plugin, (activePlugin, world, chunkX, chunkZ, task, delayTicks) ->
                FoliaScheduler.runRegion(activePlugin, world, chunkX, chunkZ, task, delayTicks));
        }

        Service(Plugin plugin, RegionScheduler scheduler) {
            this.plugin = plugin;
            this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
            sequence = new AtomicLong();
            pending = new ConcurrentHashMap<Long, Pending>();
            unavailableWorlds = ConcurrentHashMap.newKeySet();
            closed = new AtomicBoolean(false);
            lifecycleLock = new ReentrantReadWriteLock(true);
            lifecycleReadLock = lifecycleLock.readLock();
            lifecycleWriteLock = lifecycleLock.writeLock();
        }

        boolean run(
            World world,
            int chunkX,
            int chunkZ,
            Runnable task,
            Runnable retired,
            long delayTicks
        ) {
            World targetWorld = Objects.requireNonNull(world, "world");
            Runnable command = Objects.requireNonNull(task, "task");
            Runnable retirement = Objects.requireNonNull(retired, "retired");
            UUID worldId = targetWorld.getUID();
            lifecycleReadLock.lock();
            try {
                if (closed.get() || unavailableWorlds.contains(worldId)) {
                    retirement.run();
                    return false;
                }
                long id = sequence.incrementAndGet();
                Pending scheduled = new Pending(id, worldId, command, retirement);
                pending.put(Long.valueOf(id), scheduled);
                boolean accepted;
                try {
                    accepted = scheduler.schedule(
                        plugin,
                        targetWorld,
                        chunkX,
                        chunkZ,
                        () -> execute(scheduled),
                        delayTicks
                    );
                } catch (RuntimeException exception) {
                    retire(scheduled);
                    plugin.getLogger().log(
                        Level.WARNING,
                        "Could not schedule region task for world " + worldId
                            + " at chunk " + chunkX + "," + chunkZ
                            + " with delay " + delayTicks + " ticks",
                        exception
                    );
                    return false;
                }
                if (!accepted) {
                    retire(scheduled);
                }
                return accepted;
            } finally {
                lifecycleReadLock.unlock();
            }
        }

        private void execute(Pending scheduled) {
            lifecycleReadLock.lock();
            try {
                if (!scheduled.claim()) {
                    return;
                }
                pending.remove(Long.valueOf(scheduled.id()), scheduled);
                if (closed.get() || unavailableWorlds.contains(scheduled.worldId())) {
                    runRetired(scheduled);
                    return;
                }
                scheduled.task().run();
            } finally {
                lifecycleReadLock.unlock();
            }
        }

        private boolean retire(Pending scheduled) {
            if (!scheduled.claim()) {
                return false;
            }
            pending.remove(Long.valueOf(scheduled.id()), scheduled);
            runRetired(scheduled);
            return true;
        }

        private void runRetired(Pending scheduled) {
            try {
                scheduled.retired().run();
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Region task retirement failed", exception);
            }
        }

        void retireWorld(UUID worldId) {
            lifecycleWriteLock.lock();
            try {
                unavailableWorlds.add(worldId);
                for (Pending scheduled : pending.values()) {
                    if (scheduled.worldId().equals(worldId)) {
                        retire(scheduled);
                    }
                }
            } finally {
                lifecycleWriteLock.unlock();
            }
        }

        void activateWorld(UUID worldId) {
            lifecycleWriteLock.lock();
            try {
                if (!closed.get()) {
                    unavailableWorlds.remove(worldId);
                }
            } finally {
                lifecycleWriteLock.unlock();
            }
        }

        void shutdown() {
            lifecycleWriteLock.lock();
            try {
                if (!closed.compareAndSet(false, true)) {
                    return;
                }
                for (Pending scheduled : pending.values()) {
                    retire(scheduled);
                }
            } finally {
                lifecycleWriteLock.unlock();
            }
        }
    }

    private static final class Pending {
        private final long id;
        private final UUID worldId;
        private final Runnable task;
        private final Runnable retired;
        private final AtomicBoolean claimed;

        private Pending(long id, UUID worldId, Runnable task, Runnable retired) {
            this.id = id;
            this.worldId = worldId;
            this.task = task;
            this.retired = retired;
            claimed = new AtomicBoolean(false);
        }

        private long id() {
            return id;
        }

        private UUID worldId() {
            return worldId;
        }

        private Runnable task() {
            return task;
        }

        private Runnable retired() {
            return retired;
        }

        private boolean claim() {
            return claimed.compareAndSet(false, true);
        }
    }
}
