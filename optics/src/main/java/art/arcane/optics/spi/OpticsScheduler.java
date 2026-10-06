package art.arcane.optics.spi;

import java.util.concurrent.Executor;

public interface OpticsScheduler<O, W> {
    boolean runForObserver(O observer, Runnable task);

    boolean runForRegion(W world, int chunkX, int chunkZ, Runnable task);

    Executor compute();

    boolean schedule(Runnable task, long delayMillis);

    long tick();
}
