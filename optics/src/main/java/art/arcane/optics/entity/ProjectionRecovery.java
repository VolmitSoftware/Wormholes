package art.arcane.optics.entity;

import java.util.concurrent.atomic.AtomicBoolean;

public final class ProjectionRecovery<O> {
    private final Host<O> host;
    private final AtomicBoolean retryScheduled = new AtomicBoolean();
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private volatile boolean pending;

    public ProjectionRecovery(Host<O> host) {
        this.host = host;
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
        if (!host.online(observer)) {
            host.drop(observer);
            pending = false;
            host.release(observer);
            return;
        }
        if (!pending && !host.hasState()) {
            host.release(observer);
            return;
        }
        try {
            host.send(observer);
            host.drop(observer);
            recovered();
        } catch (RuntimeException error) {
            pending = true;
            report(observer, error);
            schedule(observer);
        } finally {
            host.release(observer);
        }
    }

    public void markPending(O observer) {
        pending = true;
        host.release(observer);
        schedule(observer);
    }

    private void schedule(O observer) {
        if (!host.online(observer) || !pending || !retryScheduled.compareAndSet(false, true)) {
            return;
        }
        boolean scheduled = host.schedule(observer, () -> retry(observer));
        if (!scheduled) {
            retryScheduled.set(false);
        }
    }

    private void retry(O observer) {
        retryScheduled.set(false);
        if (!pending) {
            return;
        }
        if (!host.online(observer)) {
            host.drop(observer);
            pending = false;
            return;
        }
        try {
            host.send(observer);
            host.drop(observer);
            recovered();
        } catch (RuntimeException error) {
            report(observer, error);
            schedule(observer);
        }
    }

    private void report(O observer, RuntimeException error) {
        if (failureReported.compareAndSet(false, true)) {
            host.warning(observer, error);
        }
    }

    public interface Host<O> {
        boolean online(O observer);
        boolean hasState();
        void send(O observer);
        void drop(O observer);
        void release(O observer);
        boolean schedule(O observer, Runnable task);
        void warning(O observer, RuntimeException error);
    }
}
