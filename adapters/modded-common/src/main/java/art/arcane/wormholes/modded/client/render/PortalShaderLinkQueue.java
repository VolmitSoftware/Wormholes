package art.arcane.wormholes.modded.client.render;

import java.util.ArrayDeque;
import java.util.function.LongSupplier;

final class PortalShaderLinkQueue implements AutoCloseable {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private final LongSupplier clock;
    private long steps;
    private long totalNanos;
    private long maximumNanos;
    private boolean closed;

    PortalShaderLinkQueue(LongSupplier clock) {
        this.clock = clock;
    }

    void add(Runnable task) {
        if (closed) {
            throw new IllegalStateException("Destination shader loading is closed");
        }
        tasks.add(task);
    }

    boolean advance(long budgetNanos) {
        if (closed || budgetNanos < 1) {
            throw new IllegalStateException("Destination shader loading cannot advance");
        }
        long started = clock.getAsLong();
        do {
            Runnable task = tasks.poll();
            if (task == null) {
                return true;
            }
            long taskStarted = clock.getAsLong();
            try {
                task.run();
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            } finally {
                long elapsed = clock.getAsLong() - taskStarted;
                steps++;
                totalNanos += elapsed;
                maximumNanos = Math.max(maximumNanos, elapsed);
            }
        } while (clock.getAsLong() - started < budgetNanos);
        return tasks.isEmpty();
    }

    boolean ready() {
        return !closed && tasks.isEmpty();
    }

    Stats stats() {
        return new Stats(tasks.size(), steps, totalNanos, maximumNanos);
    }

    @Override
    public void close() {
        closed = true;
        tasks.clear();
    }

    record Stats(int pending, long steps, long totalNanos, long maximumNanos) {
    }
}
