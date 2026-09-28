package art.arcane.wormholes.network.replication.capture;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class CaptureCycleLoop {
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
    private final Consumer<Throwable> failureReporter;
    private final AtomicBoolean running;
    private final AtomicBoolean rejectionReported;
    private final AtomicLong generation;
    private final AtomicLong retryGeneration;

    CaptureCycleLoop(
        long intervalTicks,
        BooleanSupplier shouldRun,
        Runnable task,
        Scheduler scheduler,
        Consumer<Runnable> retryDispatcher,
        Runnable rejectionReporter,
        Consumer<Throwable> failureReporter
    ) {
        this.intervalTicks = Math.max(1L, intervalTicks);
        this.shouldRun = Objects.requireNonNull(shouldRun, "shouldRun");
        this.task = Objects.requireNonNull(task, "task");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.retryDispatcher = Objects.requireNonNull(retryDispatcher, "retryDispatcher");
        this.rejectionReporter = Objects.requireNonNull(rejectionReporter, "rejectionReporter");
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
        this.running = new AtomicBoolean(false);
        this.rejectionReported = new AtomicBoolean(false);
        this.generation = new AtomicLong();
        this.retryGeneration = new AtomicLong();
    }

    void start() {
        if (!shouldRun.getAsBoolean() || !running.compareAndSet(false, true)) {
            return;
        }
        long activeGeneration = generation.incrementAndGet();
        scheduleNext(activeGeneration);
    }

    void stop() {
        generation.incrementAndGet();
        retryGeneration.set(0L);
        running.set(false);
    }

    boolean isRunning() {
        return running.get();
    }

    private void scheduleNext(long activeGeneration) {
        if (!isActive(activeGeneration)) {
            if (generation.get() == activeGeneration) {
                running.compareAndSet(true, false);
            }
            return;
        }
        boolean accepted;
        try {
            accepted = scheduler.schedule(() -> runScheduled(activeGeneration), intervalTicks);
        } catch (Throwable error) {
            failureReporter.accept(error);
            accepted = false;
        }
        if (accepted) {
            rejectionReported.set(false);
            return;
        }
        reject(activeGeneration);
    }

    private void runScheduled(long activeGeneration) {
        if (!isActive(activeGeneration)) {
            return;
        }
        try {
            task.run();
        } finally {
            if (isActive(activeGeneration)) {
                scheduleNext(activeGeneration);
            }
        }
    }

    private void reject(long activeGeneration) {
        if (generation.get() != activeGeneration || !running.compareAndSet(true, false)) {
            return;
        }
        if (rejectionReported.compareAndSet(false, true)) {
            try {
                rejectionReporter.run();
            } catch (Throwable error) {
                failureReporter.accept(error);
            }
        }
        retryGeneration.set(activeGeneration);
        try {
            retryDispatcher.accept(() -> {
                if (!retryGeneration.compareAndSet(activeGeneration, 0L)) {
                    return;
                }
                if (generation.get() == activeGeneration && shouldRun.getAsBoolean()) {
                    start();
                }
            });
        } catch (Throwable error) {
            retryGeneration.compareAndSet(activeGeneration, 0L);
            failureReporter.accept(error);
        }
    }

    private boolean isActive(long activeGeneration) {
        return running.get()
            && generation.get() == activeGeneration
            && shouldRun.getAsBoolean();
    }
}
