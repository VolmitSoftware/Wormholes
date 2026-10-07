package art.arcane.optics.internal.stream;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ViewStreamLane implements Runnable {
    private final Executor executor;
    private final Runnable body;
    private final AtomicBoolean scheduled;
    private final AtomicBoolean pending;

    public ViewStreamLane(Executor executor, Runnable body) {
        this.executor = executor;
        this.body = body;
        this.scheduled = new AtomicBoolean();
        this.pending = new AtomicBoolean();
    }

    public void submit() {
        pending.set(true);
        schedule();
    }

    public boolean stalled() {
        return pending.get() && !scheduled.get();
    }

    @Override
    public void run() {
        try {
            while (pending.getAndSet(false)) {
                body.run();
            }
        } finally {
            scheduled.set(false);
        }
        if (pending.get()) {
            schedule();
        }
    }

    private void schedule() {
        if (!scheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(this);
        } catch (RejectedExecutionException rejected) {
            scheduled.set(false);
        }
    }
}
