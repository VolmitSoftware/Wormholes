package art.arcane.wormholes.network.view;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class ViewTaskLoop {
    @FunctionalInterface
    interface Scheduler {
        boolean schedule(Runnable task, long delayTicks);
    }

    private final long intervalTicks;
    private final BooleanSupplier shouldRun;
    private final Runnable task;
    private final Scheduler scheduler;
    private final Consumer<Runnable> retryDispatcher;
    private final Runnable rejectionReporter;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean retryQueued = new AtomicBoolean(false);
    private final AtomicBoolean rejectionReported = new AtomicBoolean(false);

    ViewTaskLoop(long intervalTicks, BooleanSupplier shouldRun, Runnable task, Scheduler scheduler,
                 Consumer<Runnable> retryDispatcher, Runnable rejectionReporter) {
        this.intervalTicks = Math.max(1L, intervalTicks);
        this.shouldRun = shouldRun;
        this.task = task;
        this.scheduler = scheduler;
        this.retryDispatcher = retryDispatcher;
        this.rejectionReporter = rejectionReporter;
    }

    void start() {
        if (stopped.get() || !shouldRun.getAsBoolean() || !running.compareAndSet(false, true)) {
            return;
        }
        scheduleNext();
    }

    void stop() {
        stopped.set(true);
        running.set(false);
    }

    boolean isRunning() {
        return running.get();
    }

    private void scheduleNext() {
        if (stopped.get()) {
            running.set(false);
            return;
        }
        if (scheduler.schedule(this::runScheduled, intervalTicks)) {
            rejectionReported.set(false);
            return;
        }
        running.set(false);
        if (stopped.get() || !shouldRun.getAsBoolean()) {
            return;
        }
        if (rejectionReported.compareAndSet(false, true)) {
            rejectionReporter.run();
        }
        queueRetry();
    }

    private void runScheduled() {
        if (stopped.get() || !running.get()) {
            return;
        }
        if (stopped.get() || !shouldRun.getAsBoolean()) {
            releaseAndRestartIfNeeded();
            return;
        }
        try {
            task.run();
        } finally {
            if (!shouldRun.getAsBoolean()) {
                releaseAndRestartIfNeeded();
            } else {
                scheduleNext();
            }
        }
    }

    private void releaseAndRestartIfNeeded() {
        running.set(false);
        if (!stopped.get() && shouldRun.getAsBoolean()) {
            start();
        }
    }

    private void queueRetry() {
        if (!retryQueued.compareAndSet(false, true)) {
            return;
        }
        retryDispatcher.accept(() -> {
            retryQueued.set(false);
            start();
        });
    }
}
