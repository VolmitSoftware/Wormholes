package art.arcane.optics.plate;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs plate builds on a bounded pool of host-named threads. Every view a build reads is safe
 * off-thread: region snapshots, remote views and chunk snapshots captured for the plate.
 */
public final class PlateWorkers implements Executor {
    private static final int QUEUE_CAPACITY = 256;

    private final String threadPrefix;
    private final AtomicInteger threadSequence = new AtomicInteger();
    private volatile ThreadPoolExecutor executor;

    public PlateWorkers(String threadPrefix, int threads) {
        this.threadPrefix = threadPrefix;
        this.executor = createExecutor(threads);
    }

    @Override
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

    private ThreadPoolExecutor createExecutor(int threads) {
        int size = Math.max(1, threads);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, threadPrefix + threadSequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(size, size, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(QUEUE_CAPACITY), factory, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
