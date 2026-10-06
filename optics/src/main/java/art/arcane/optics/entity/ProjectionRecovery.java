package art.arcane.optics.entity;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import art.arcane.optics.spi.OpticsScheduler;

public final class ProjectionRecovery<O> {
    private final EntityOutput<O, ?, ?, ?, ?> output;
    private final OpticsScheduler<O, ?> scheduler;
    private final Teardown<O> steps;
    private final AtomicBoolean retryScheduled = new AtomicBoolean();
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private volatile boolean pending;

    public ProjectionRecovery(EntityOutput<O, ?, ?, ?, ?> output, OpticsScheduler<O, ?> scheduler, Teardown<O> teardown) {
        this.output = Objects.requireNonNull(output);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.steps = Objects.requireNonNull(teardown);
    }

    public boolean pending() {
        return pending;
    }

    public void recovered() {
        pending = false;
        failureReported.set(false);
    }

    public void clearPending() {
        pending = false;
    }

    public void teardown(O observer) {
        if (!output.online(observer)) {
            steps.drop().accept(observer);
            pending = false;
            steps.release().accept(observer);
            return;
        }
        if (!pending && !steps.state().getAsBoolean()) {
            steps.release().accept(observer);
            return;
        }
        try {
            steps.send().accept(observer);
            steps.drop().accept(observer);
            recovered();
        } catch (RuntimeException error) {
            pending = true;
            report(observer, error);
            schedule(observer);
        } finally {
            steps.release().accept(observer);
        }
    }

    public void markPending(O observer) {
        pending = true;
        steps.release().accept(observer);
        schedule(observer);
    }

    private void schedule(O observer) {
        if (!output.online(observer) || !pending || !retryScheduled.compareAndSet(false, true)) {
            return;
        }
        boolean scheduled = scheduler.runForObserver(observer, () -> retry(observer));
        if (!scheduled) {
            retryScheduled.set(false);
        }
    }

    private void retry(O observer) {
        retryScheduled.set(false);
        if (!pending) {
            return;
        }
        if (!output.online(observer)) {
            steps.drop().accept(observer);
            pending = false;
            return;
        }
        try {
            steps.send().accept(observer);
            steps.drop().accept(observer);
            recovered();
        } catch (RuntimeException error) {
            report(observer, error);
            schedule(observer);
        }
    }

    private void report(O observer, RuntimeException error) {
        if (failureReported.compareAndSet(false, true)) {
            output.warning(observer, "failed to send projected entity teardown", error);
        }
    }

    public record Teardown<O>(BooleanSupplier state, Consumer<O> send, Consumer<O> drop, Consumer<O> release) {
        public Teardown {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(send, "send");
            Objects.requireNonNull(drop, "drop");
            Objects.requireNonNull(release, "release");
        }
    }
}
