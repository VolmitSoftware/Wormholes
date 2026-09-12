package art.arcane.wormholes.render.plate;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;

/**
 * Runs plate builds either on the bounded {@code Wormholes-Plate-N} pool (snapshot and remote views,
 * which are safe to read off-thread) or, for live Paper views, on the destination region thread in
 * time slices of {@link #REGION_CELLS_PER_TICK} cells per tick.
 */
public final class PlateWorkers {
    public interface PlateSink {
        void publish(ViewPlate plate);

        void failed(ViewPlateKey key);
    }

    static final int ASYNC_CELLS_PER_STEP = 8192;
    static final int REGION_CELLS_PER_TICK = 6144;
    private static final int QUEUE_CAPACITY = 256;
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final PlateSink sink;
    private volatile ThreadPoolExecutor executor;

    public PlateWorkers(int threads, PlateSink sink) {
        this.sink = sink;
        this.executor = createExecutor(threads);
    }

    public void submitAsync(ViewPlateBuilder.Job job) {
        ThreadPoolExecutor active = executor;
        if (active == null) {
            sink.failed(job.key());
            return;
        }
        try {
            active.execute(() -> runToCompletion(job));
        } catch (RejectedExecutionException rejected) {
            sink.failed(job.key());
        }
    }

    public void submitRegion(Plugin plugin, World world, int chunkX, int chunkZ, ViewPlateBuilder.Job job) {
        boolean scheduled = FoliaScheduler.runRegion(plugin, world, chunkX, chunkZ,
            () -> stepOnRegion(plugin, world, chunkX, chunkZ, job));
        if (!scheduled) {
            sink.failed(job.key());
        }
    }

    public void resize(int threads) {
        ThreadPoolExecutor active = executor;
        int target = Math.max(1, threads);
        if (active == null || active.getCorePoolSize() == target) {
            return;
        }
        if (target > active.getCorePoolSize()) {
            active.setMaximumPoolSize(target);
            active.setCorePoolSize(target);
        } else {
            active.setCorePoolSize(target);
            active.setMaximumPoolSize(target);
        }
    }

    public int threads() {
        ThreadPoolExecutor active = executor;
        return active == null ? 0 : active.getCorePoolSize();
    }

    public void shutdown() {
        ThreadPoolExecutor active = executor;
        executor = null;
        if (active != null) {
            active.shutdownNow();
        }
    }

    private void runToCompletion(ViewPlateBuilder.Job job) {
        try {
            while (!job.step(ASYNC_CELLS_PER_STEP)) {
                if (Thread.currentThread().isInterrupted()) {
                    sink.failed(job.key());
                    return;
                }
            }
            sink.publish(job.result());
        } catch (RuntimeException failure) {
            sink.failed(job.key());
            Wormholes plugin = Wormholes.instance;
            if (plugin != null) {
                plugin.getLogger().log(Level.WARNING, "[plate] build failed for portal " + job.key().portalId(), failure);
            }
        }
    }

    private void stepOnRegion(Plugin plugin, World world, int chunkX, int chunkZ, ViewPlateBuilder.Job job) {
        boolean finished;
        try {
            finished = job.step(REGION_CELLS_PER_TICK);
        } catch (RuntimeException failure) {
            sink.failed(job.key());
            plugin.getLogger().log(Level.WARNING, "[plate] region build failed for portal " + job.key().portalId(), failure);
            return;
        }
        if (finished) {
            sink.publish(job.result());
            return;
        }
        boolean scheduled = FoliaScheduler.runRegion(plugin, world, chunkX, chunkZ,
            () -> stepOnRegion(plugin, world, chunkX, chunkZ, job), 1L);
        if (!scheduled) {
            sink.failed(job.key());
        }
    }

    private static ThreadPoolExecutor createExecutor(int threads) {
        int size = Math.max(1, threads);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "Wormholes-Plate-" + THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(size, size, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(QUEUE_CAPACITY), factory, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
