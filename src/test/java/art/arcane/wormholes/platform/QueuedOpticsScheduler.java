package art.arcane.wormholes.platform;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

import org.bukkit.World;
import org.bukkit.entity.Player;

import art.arcane.optics.spi.OpticsScheduler;

public final class QueuedOpticsScheduler implements OpticsScheduler<Player, World> {
    private final Queue<Runnable> owned = new ConcurrentLinkedQueue<Runnable>();

    @Override
    public boolean runForObserver(Player observer, Runnable task) {
        return owned.add(task);
    }

    @Override
    public boolean runForRegion(World world, int chunkX, int chunkZ, Runnable task) {
        return owned.add(task);
    }

    @Override
    public Executor compute() {
        return Runnable::run;
    }

    @Override
    public boolean schedule(Runnable task, long delayMillis) {
        return owned.add(task);
    }

    @Override
    public long tick() {
        return 0L;
    }

    public int pending() {
        return owned.size();
    }

    public int runPending() {
        int ran = 0;
        Runnable task = owned.poll();
        while (task != null) {
            task.run();
            ran++;
            task = owned.poll();
        }
        return ran;
    }
}
