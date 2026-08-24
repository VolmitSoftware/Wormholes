package art.arcane.wormholes.network.replication.capture;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.platform.BukkitRegionTaskProvider;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class CaptureRegionScheduler {
    static final int MAX_REGION_DRAINS_PER_TICK = 64;

    private final Plugin plugin;
    private final RegionalDiffAccumulator accumulator;
    private final LightDiffCapture lightDiffCapture;
    private final boolean folia;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Set<DrainKey> drainsInFlight = ConcurrentHashMap.newKeySet();
    private final CaptureDrainBudget drainBudget = new CaptureDrainBudget();
    private final CaptureCycleLoop foliaCycle;
    private int globalTaskId = -1;

    public CaptureRegionScheduler(Plugin plugin, RegionalDiffAccumulator accumulator, LightDiffCapture lightDiffCapture) {
        this.plugin = plugin;
        this.accumulator = accumulator;
        this.lightDiffCapture = lightDiffCapture;
        this.folia = detectFolia();
        this.foliaCycle = new CaptureCycleLoop(
            1L,
            running::get,
            this::dispatchRegionalDrains,
            (task, delayTicks) -> FoliaScheduler.runGlobal(plugin, task, delayTicks),
            task -> CompletableFuture.delayedExecutor(1L, TimeUnit.SECONDS).execute(task),
            () -> plugin.getLogger().warning("Replication capture maintenance was rejected; retrying in one second"),
            error -> plugin.getLogger().log(Level.WARNING, "Replication capture maintenance scheduling failed", error)
        );
    }

    public boolean isFolia() {
        return folia;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        if (folia) {
            foliaCycle.start();
            return;
        }
        globalTaskId = Bukkit.getScheduler().runTaskTimer(plugin, this::runPaperDrain, 1L, 1L).getTaskId();
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (!folia && globalTaskId != -1) {
            try {
                Bukkit.getScheduler().cancelTask(globalTaskId);
            } catch (Throwable ignored) {
            }
            globalTaskId = -1;
        }
        foliaCycle.stop();
        drainsInFlight.clear();
        if (!folia) {
            try {
                accumulator.drainAll(makeHook());
            } catch (Throwable ignored) {
            }
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    private RegionalDiffAccumulator.PreDrainHook makeHook() {
        if (lightDiffCapture == null) {
            return null;
        }
        return lightDiffCapture::sampleAround;
    }

    private void runPaperDrain() {
        RegionalDiffAccumulator.PreDrainHook hook = makeHook();
        for (DrainKey key : selectDrainCandidates()) {
            World world = Bukkit.getWorld(key.worldId());
            if (world != null) {
                accumulator.drainChunk(world, key.chunkKey(), hook);
            }
        }
    }

    private void dispatchRegionalDrains() {
        if (!running.get()) {
            return;
        }
        RegionalDiffAccumulator.PreDrainHook hook = makeHook();
        for (DrainKey key : selectDrainCandidates()) {
            dispatchRegionalDrain(key, hook);
        }
    }

    private List<DrainKey> selectDrainCandidates() {
        List<DrainKey> candidates = new ArrayList<DrainKey>();
        for (Map.Entry<UUID, Map<Long, ChunkDirtySet>> worldEntry : accumulator.dirtyWorlds().entrySet()) {
            if (Bukkit.getWorld(worldEntry.getKey()) == null) {
                continue;
            }
            for (Map.Entry<Long, ChunkDirtySet> chunkEntry : worldEntry.getValue().entrySet()) {
                long chunkKey = chunkEntry.getKey();
                if (chunkEntry.getValue().isEmpty() && !accumulator.hasPendingLight(worldEntry.getKey(), chunkKey)) {
                    continue;
                }
                candidates.add(new DrainKey(worldEntry.getKey(), chunkKey));
            }
        }
        return drainBudget.select(candidates, drainsInFlight, MAX_REGION_DRAINS_PER_TICK);
    }

    private void dispatchRegionalDrain(DrainKey key, RegionalDiffAccumulator.PreDrainHook hook) {
        World world = Bukkit.getWorld(key.worldId());
        if (world == null || !drainsInFlight.add(key)) {
            return;
        }
        int chunkX = (int) (key.chunkKey() >> 32);
        int chunkZ = (int) key.chunkKey();
        Runnable complete = () -> drainsInFlight.remove(key);
        boolean scheduled = BukkitRegionTaskProvider.run(
            world,
            chunkX,
            chunkZ,
            () -> {
                try {
                    if (running.get()) {
                        accumulator.drainChunk(world, key.chunkKey(), hook);
                    }
                } finally {
                    complete.run();
                }
            },
            complete,
            0L
        );
        if (!scheduled) {
            complete.run();
        }
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    record DrainKey(UUID worldId, long chunkKey) {
    }
}
