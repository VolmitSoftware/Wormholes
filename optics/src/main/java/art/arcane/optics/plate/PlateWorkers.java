package art.arcane.optics.plate;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs plate builds on the bounded {@code Wormholes-Plate-N} pool. Every view a build reads is safe
 * off-thread: region snapshots, remote views and chunk snapshots captured for the plate.
 */
public final class PlateWorkers<B, W> {
    public interface Host<B, W> {
        void publish(ViewPlateBuilder.Job<B, W> job, ViewPlate<B> plate);

        void failed(ViewPlateBuilder.Job<B, W> job);

        void warning(ViewPlateKey key, RuntimeException failure);
    }

    static final int ASYNC_CELLS_PER_STEP = 8192;
    private static final int QUEUE_CAPACITY = 256;
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final Host<B, W> host;
    private volatile ThreadPoolExecutor executor;

    public PlateWorkers(int threads, Host<B, W> host) {
        this.host = host;
        this.executor = createExecutor(threads);
    }

    public void submitAsync(ViewPlateBuilder.Job<B, W> job) {
        ThreadPoolExecutor active = executor;
        if (active == null) {
            host.failed(job);
            return;
        }
        try {
            active.execute(() -> runToCompletion(job));
        } catch (RejectedExecutionException rejected) {
            host.failed(job);
        }
    }

    public void execute(Runnable task) {
        ThreadPoolExecutor active = executor;
        if (active == null) {
            throw new RejectedExecutionException("plate workers are shut down");
        }
        active.execute(task);
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

    private void runToCompletion(ViewPlateBuilder.Job<B, W> job) {
        try {
            while (!job.step(ASYNC_CELLS_PER_STEP)) {
                if (Thread.currentThread().isInterrupted()) {
                    host.failed(job);
                    return;
                }
            }
            host.publish(job, job.result());
        } catch (RuntimeException failure) {
            host.failed(job);
            host.warning(job.key(), failure);
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
