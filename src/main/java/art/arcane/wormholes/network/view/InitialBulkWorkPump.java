package art.arcane.wormholes.network.view;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

final class InitialBulkWorkPump {
    private static final Consumer<WorkHandle> NOOP_ASSOCIATION = ignored -> {
    };

    private final Object lock = new Object();
    private final ArrayDeque<WorkHandle> pending = new ArrayDeque<WorkHandle>();
    private final Scheduler scheduler;
    private final int maxWorkPerPass;
    private final long delayTicks;
    private final Consumer<Throwable> failureSink;

    private boolean scheduled;
    private boolean closed;

    InitialBulkWorkPump(Scheduler scheduler, int maxWorkPerPass, long delayTicks,
                        Consumer<Throwable> failureSink) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        if (maxWorkPerPass <= 0) {
            throw new IllegalArgumentException("maxWorkPerPass must be positive");
        }
        if (delayTicks < 0L) {
            throw new IllegalArgumentException("delayTicks must not be negative");
        }
        this.maxWorkPerPass = maxWorkPerPass;
        this.delayTicks = delayTicks;
        this.failureSink = Objects.requireNonNull(failureSink, "failureSink");
    }

    WorkHandle enqueue(Work work) {
        return enqueue(work, NOOP_ASSOCIATION);
    }

    WorkHandle enqueue(Work work, Consumer<WorkHandle> association) {
        Objects.requireNonNull(work, "work");
        Objects.requireNonNull(association, "association");
        WorkHandle handle = new WorkHandle(work);
        boolean start;
        synchronized (lock) {
            if (closed) {
                return null;
            }
            pending.addLast(handle);
            try {
                association.accept(handle);
            } catch (RuntimeException | Error failure) {
                pending.remove(handle);
                handle.cancelled = true;
                throw failure;
            }
            start = !scheduled;
            if (start) {
                scheduled = true;
            }
        }
        if (start) {
            schedule(0L);
        }
        return handle;
    }

    void cancel(WorkHandle handle) {
        if (handle == null) {
            return;
        }
        synchronized (lock) {
            handle.cancelled = true;
            pending.remove(handle);
        }
    }

    void close() {
        synchronized (lock) {
            closed = true;
            scheduled = false;
            for (WorkHandle handle : pending) {
                handle.cancelled = true;
            }
            pending.clear();
        }
    }

    int pendingWorkCount() {
        synchronized (lock) {
            return pending.size();
        }
    }

    private void runPass() {
        for (int dispatched = 0; dispatched < maxWorkPerPass; dispatched++) {
            WorkHandle handle = poll();
            if (handle == null) {
                break;
            }
            Work work = handle.work;
            boolean more;
            try {
                more = work.runNext();
            } catch (Throwable failure) {
                reject(work, failure);
                more = false;
            }
            if (more) {
                requeue(handle);
            }
        }

        synchronized (lock) {
            if (closed || pending.isEmpty()) {
                scheduled = false;
                return;
            }
        }
        schedule(delayTicks);
    }

    private WorkHandle poll() {
        synchronized (lock) {
            if (closed) {
                return null;
            }
            return pending.pollFirst();
        }
    }

    private void requeue(WorkHandle handle) {
        synchronized (lock) {
            if (!closed && !handle.cancelled) {
                pending.addLast(handle);
            }
        }
    }

    private void schedule(long requestedDelayTicks) {
        boolean accepted;
        try {
            accepted = scheduler.schedule(this::runPass, requestedDelayTicks);
        } catch (Throwable failure) {
            rejectPending(failure);
            return;
        }
        if (accepted) {
            return;
        }
        rejectPending(null);
    }

    private void rejectPending(Throwable failure) {
        List<WorkHandle> rejected;
        synchronized (lock) {
            if (closed) {
                scheduled = false;
                return;
            }
            rejected = new ArrayList<WorkHandle>(pending);
            for (WorkHandle handle : rejected) {
                handle.cancelled = true;
            }
            pending.clear();
            scheduled = false;
        }
        for (WorkHandle handle : rejected) {
            reject(handle.work, failure);
        }
    }

    private void reject(Work work, Throwable failure) {
        try {
            work.reject(failure);
        } catch (Throwable rejectionFailure) {
            if (failure != null && failure != rejectionFailure) {
                rejectionFailure.addSuppressed(failure);
            }
            reportFailure(rejectionFailure);
        }
    }

    private void reportFailure(Throwable failure) {
        try {
            failureSink.accept(failure);
        } catch (Throwable sinkFailure) {
            failure.addSuppressed(sinkFailure);
            failure.printStackTrace();
        }
    }

    @FunctionalInterface
    interface Scheduler {
        boolean schedule(Runnable task, long delayTicks);
    }

    interface Work {
        boolean runNext();

        void reject(Throwable failure);
    }

    static final class WorkHandle {
        private final Work work;
        private volatile boolean cancelled;

        private WorkHandle(Work work) {
            this.work = work;
        }

        boolean isCancelled() {
            return cancelled;
        }
    }
}
