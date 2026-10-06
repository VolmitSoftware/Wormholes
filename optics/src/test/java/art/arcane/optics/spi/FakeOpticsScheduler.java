package art.arcane.optics.spi;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

public final class FakeOpticsScheduler<O, W> implements OpticsScheduler<O, W> {
    private final ArrayDeque<Runnable> computeTasks = new ArrayDeque<Runnable>();
    private final ArrayDeque<Runnable> observerTasks = new ArrayDeque<Runnable>();
    private final ArrayDeque<Runnable> regionTasks = new ArrayDeque<Runnable>();
    private final List<Delayed> delayed = new ArrayList<Delayed>();
    private final Executor compute;
    private long tick;
    private long nowMillis;
    private boolean rejecting;

    public FakeOpticsScheduler() {
        this.compute = this::submitCompute;
    }

    public FakeOpticsScheduler(Executor compute) {
        this.compute = compute;
    }

    @Override
    public boolean runForObserver(O observer, Runnable task) {
        return !rejecting && observerTasks.add(task);
    }

    @Override
    public boolean runForRegion(W world, int chunkX, int chunkZ, Runnable task) {
        return !rejecting && regionTasks.add(task);
    }

    @Override
    public Executor compute() {
        return compute;
    }

    @Override
    public boolean schedule(Runnable task, long delayMillis) {
        if (rejecting) {
            return false;
        }
        delayed.add(new Delayed(task, nowMillis + Math.max(0L, delayMillis)));
        return true;
    }

    @Override
    public long tick() {
        return tick;
    }

    public void advanceTicks(long ticks) {
        tick += ticks;
    }

    public void reject(boolean rejecting) {
        this.rejecting = rejecting;
    }

    public int pendingCompute() {
        return computeTasks.size();
    }

    public int pendingScheduled() {
        return delayed.size();
    }

    public int runCompute() {
        int ran = 0;
        Runnable task = computeTasks.poll();
        while (task != null) {
            task.run();
            ran++;
            task = computeTasks.poll();
        }
        return ran;
    }

    public int runObserverTasks() {
        return drain(observerTasks);
    }

    public int runRegionTasks() {
        return drain(regionTasks);
    }

    public int advanceMillis(long millis) {
        nowMillis += millis;
        int ran = 0;
        for (int index = 0; index < delayed.size(); ) {
            Delayed entry = delayed.get(index);
            if (entry.dueMillis() > nowMillis) {
                index++;
                continue;
            }
            delayed.remove(index);
            entry.task().run();
            ran++;
            index = 0;
        }
        return ran;
    }

    private void submitCompute(Runnable task) {
        if (rejecting) {
            throw new RejectedExecutionException("compute is shut down");
        }
        computeTasks.add(task);
    }

    private static int drain(ArrayDeque<Runnable> tasks) {
        int ran = 0;
        Runnable task = tasks.poll();
        while (task != null) {
            task.run();
            ran++;
            task = tasks.poll();
        }
        return ran;
    }

    private record Delayed(Runnable task, long dueMillis) {
    }
}
