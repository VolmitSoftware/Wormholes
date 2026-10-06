package art.arcane.wormholes.modded;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;

import art.arcane.optics.plate.PlateWorkers;
import art.arcane.optics.spi.OpticsScheduler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class MinecraftOpticsScheduler implements OpticsScheduler<ServerPlayer, ServerLevel> {
    private static final long MILLIS_PER_TICK = 50L;

    private final WormholesModRuntime runtime;
    private final LongSupplier ticks;
    private final Executor compute;
    private volatile PlateWorkers workers;

    public MinecraftOpticsScheduler(WormholesModRuntime runtime, LongSupplier ticks) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.ticks = Objects.requireNonNull(ticks, "ticks");
        this.compute = this::executeCompute;
    }

    public void start(int threads) {
        PlateWorkers previous = workers;
        workers = new PlateWorkers("Wormholes-Plate-", threads);
        if (previous != null) {
            previous.shutdown();
        }
    }

    public void resize(int threads) {
        PlateWorkers active = workers;
        if (active != null) {
            active.resize(threads);
        }
    }

    public void shutdown() {
        PlateWorkers active = workers;
        workers = null;
        if (active != null) {
            active.shutdown();
        }
    }

    @Override
    public boolean runForObserver(ServerPlayer observer, Runnable task) {
        return runtime.schedule(task, 1L);
    }

    @Override
    public boolean runForRegion(ServerLevel world, int chunkX, int chunkZ, Runnable task) {
        return runtime.schedule(task, 1L);
    }

    @Override
    public Executor compute() {
        return compute;
    }

    @Override
    public boolean schedule(Runnable task, long delayMillis) {
        return runtime.schedule(task, Math.max(1L, Math.ceilDiv(delayMillis, MILLIS_PER_TICK)));
    }

    @Override
    public long tick() {
        return ticks.getAsLong();
    }

    private void executeCompute(Runnable task) {
        PlateWorkers active = workers;
        if (active == null) {
            throw new RejectedExecutionException("Wormholes plate workers are not running");
        }
        active.execute(task);
    }
}
