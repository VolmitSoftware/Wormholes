package art.arcane.wormholes.network.replication.capture;

import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.network.replication.BlockChange;
import art.arcane.wormholes.network.replication.ChunkReplicationManager;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ChunkSnapshotComparator {
    private static final int SURFACE_SCAN_MARGIN = 8;
    private static final int MAX_PROBES_PER_TICK = 4;
    private static final int MAX_CHANGED_CELLS_PER_BATCH = 32;
    static final int INTEGRITY_BACKSTOP_SWEEPS = 12;

    private record PendingProbe(World world, long chunkKey, int chunkX, int chunkZ) {
    }

    private record SnapshotChange(int worldX, int worldY, int worldZ, BlockData data) {
    }

    private record SnapshotComparison(List<SnapshotChange> changes, boolean resyncRequired) {
    }

    private final Plugin plugin;
    private final ChunkReplicationManager replication;
    private final RegionalDiffAccumulator accumulator;
    private final Logger logger;
    private volatile CaptureSettings settings;
    private final boolean folia;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private int paperTaskId = -1;
    private final ArrayDeque<PendingProbe> probeQueue = new ArrayDeque<>();
    private final Map<UUID, Map<Long, ChunkSnapshot>> worldShadows = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Long, Integer>> captureSweepCounts = new ConcurrentHashMap<>();
    private final AtomicLong sweepsRun = new AtomicLong();
    private final AtomicLong chunksProbed = new AtomicLong();
    private final AtomicLong divergencesEmitted = new AtomicLong();

    public ChunkSnapshotComparator(Plugin plugin, ChunkReplicationManager replication, RegionalDiffAccumulator accumulator, CaptureSettings settings, Logger logger) {
        this.plugin = plugin;
        this.replication = replication;
        this.accumulator = accumulator;
        this.settings = settings == null ? CaptureSettings.defaults() : settings;
        this.logger = logger;
        this.folia = detectFolia();
    }

    public void applySettings(CaptureSettings next) {
        this.settings = next == null ? CaptureSettings.defaults() : next;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        scheduleNext();
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (!folia && paperTaskId != -1) {
            try {
                Bukkit.getScheduler().cancelTask(paperTaskId);
            } catch (Throwable ignored) {
            }
            paperTaskId = -1;
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public Stats stats() {
        return new Stats(sweepsRun.get(), chunksProbed.get(), divergencesEmitted.get());
    }

    public void evict(UUID worldId, long chunkKey) {
        Map<Long, ChunkSnapshot> shadowMap = worldShadows.get(worldId);
        if (shadowMap != null) {
            shadowMap.remove(chunkKey);
        }
        Map<Long, Integer> sweepMap = captureSweepCounts.get(worldId);
        if (sweepMap != null) {
            sweepMap.remove(chunkKey);
        }
    }

    public record Stats(long sweepsRun, long chunksProbed, long divergencesEmitted) {
    }

    private void scheduleNext() {
        if (!running.get()) {
            return;
        }
        long delay = Math.max(20L, settings.snapshotIntervalTicks());
        if (folia) {
            FoliaScheduler.runGlobal(plugin, this::runSweep, delay);
            return;
        }
        paperTaskId = Bukkit.getScheduler().runTaskLater(plugin, this::runSweep, delay).getTaskId();
    }

    private void runSweep() {
        if (!running.get()) {
            return;
        }
        try {
            sweepsRun.incrementAndGet();
            for (World world : Bukkit.getWorlds()) {
                List<Long> keys = replication.subscribedChunkKeys(world.getUID());
                for (Long keyBoxed : keys) {
                    long chunkKey = keyBoxed.longValue();
                    int chunkX = (int) (chunkKey >> 32);
                    int chunkZ = (int) chunkKey;
                    probeQueue.add(new PendingProbe(world, chunkKey, chunkX, chunkZ));
                }
            }
        } catch (Throwable ex) {
            if (logger != null) {
                logger.log(Level.WARNING, "Snapshot-diff sweep failure", ex);
            }
        } finally {
            if (probeQueue.isEmpty()) {
                scheduleNext();
            } else {
                drainProbeQueue();
            }
        }
    }

    private void drainProbeQueue() {
        if (!running.get()) {
            probeQueue.clear();
            return;
        }
        int processed = 0;
        while (processed < MAX_PROBES_PER_TICK && !probeQueue.isEmpty()) {
            PendingProbe probe = probeQueue.poll();
            if (folia) {
                FoliaScheduler.runRegion(plugin, probe.world(), probe.chunkX(), probe.chunkZ(),
                    () -> probeChunk(probe.world(), probe.chunkKey(), probe.chunkX(), probe.chunkZ()));
            } else {
                probeChunk(probe.world(), probe.chunkKey(), probe.chunkX(), probe.chunkZ());
            }
            processed++;
        }
        if (probeQueue.isEmpty()) {
            scheduleNext();
            return;
        }
        if (folia) {
            FoliaScheduler.runGlobal(plugin, this::drainProbeQueue, 1L);
        } else {
            paperTaskId = Bukkit.getScheduler().runTaskLater(plugin, this::drainProbeQueue, 1L).getTaskId();
        }
    }

    boolean shouldCaptureSnapshot(UUID worldId, long chunkKey) {
        Map<Long, Integer> sweepMap = captureSweepCounts.computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<>());
        Integer previous = sweepMap.putIfAbsent(chunkKey, Integer.valueOf(0));
        if (previous == null) {
            return true;
        }
        int elapsedSweeps = sweepMap.merge(chunkKey, Integer.valueOf(1),
            (current, increment) -> Integer.valueOf(current.intValue() + increment.intValue())).intValue();
        if (elapsedSweeps < INTEGRITY_BACKSTOP_SWEEPS) {
            return false;
        }
        sweepMap.put(chunkKey, Integer.valueOf(0));
        return true;
    }

    private void probeChunk(World world, long chunkKey, int chunkX, int chunkZ) {
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return;
        }
        if (!replication.hasSubscribers(world, chunkKey)) {
            return;
        }
        UUID worldId = world.getUID();
        if (!shouldCaptureSnapshot(worldId, chunkKey)) {
            return;
        }
        Chunk chunk;
        try {
            chunk = world.getChunkAt(chunkX, chunkZ);
        } catch (Throwable ignored) {
            resetCaptureCadence(worldId, chunkKey);
            return;
        }
        chunksProbed.incrementAndGet();
        ChunkSnapshot snapshot;
        try {
            snapshot = WormholesPlatform.chunkSnapshot(chunk, true, false, false, false);
        } catch (Throwable ignored) {
            resetCaptureCadence(worldId, chunkKey);
            return;
        }
        int minHeight = world.getMinHeight();
        int maxHeight = world.getMaxHeight();
        boolean scheduled = FoliaScheduler.runAsync(plugin,
            () -> compareSnapshot(world, worldId, chunkKey, chunkX, chunkZ, snapshot, minHeight, maxHeight));
        if (!scheduled) {
            resetCaptureCadence(worldId, chunkKey);
        }
    }

    private void compareSnapshot(World world,
                                 UUID worldId,
                                 long chunkKey,
                                 int chunkX,
                                 int chunkZ,
                                 ChunkSnapshot snapshot,
                                 int minHeight,
                                 int maxHeight) {
        Map<Long, ChunkSnapshot> worldMap = worldShadows.computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<>());
        ChunkSnapshot previous = worldMap.put(chunkKey, snapshot);
        if (previous == null) {
            return;
        }
        int maxChangedCells = Math.max(1, settings.maxQueuedDiffsPerChunk() / 7);
        List<SnapshotChange> changes = new ArrayList<SnapshotChange>(Math.min(32, maxChangedCells));
        boolean resyncRequired = false;
        int ceiling = maxHeight - 1;
        scan:
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int curTop = snapshot.getHighestBlockYAt(x, z);
                int prevTop = previous.getHighestBlockYAt(x, z);
                int top = Math.min(ceiling, Math.max(curTop, prevTop) + SURFACE_SCAN_MARGIN);
                for (int y = minHeight; y <= top; y++) {
                    BlockData current;
                    BlockData prior;
                    try {
                        current = snapshot.getBlockData(x, y, z);
                        prior = previous.getBlockData(x, y, z);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (current == null || current.equals(prior)) {
                        continue;
                    }
                    if (changes.size() >= maxChangedCells) {
                        resyncRequired = true;
                        break scan;
                    }
                    changes.add(new SnapshotChange((chunkX << 4) | x, y, (chunkZ << 4) | z, current));
                }
            }
        }
        if (changes.isEmpty() && !resyncRequired) {
            return;
        }
        divergencesEmitted.incrementAndGet();
        SnapshotComparison comparison = new SnapshotComparison(List.copyOf(changes), resyncRequired);
        if (plugin == null) {
            applyComparisonBatch(world, worldId, chunkKey, chunkX, chunkZ, snapshot, minHeight, maxHeight, comparison, 0);
            return;
        }
        FoliaScheduler.runRegion(plugin, world, chunkX, chunkZ,
            () -> applyComparisonBatch(world, worldId, chunkKey, chunkX, chunkZ, snapshot, minHeight, maxHeight, comparison, 0));
    }

    private void applyComparisonBatch(World world,
                                      UUID worldId,
                                      long chunkKey,
                                      int chunkX,
                                      int chunkZ,
                                      ChunkSnapshot snapshot,
                                      int minHeight,
                                      int maxHeight,
                                      SnapshotComparison comparison,
                                      int startIndex) {
        if (!replication.hasSubscribers(world, chunkKey)) {
            evict(worldId, chunkKey);
            return;
        }
        if (comparison.resyncRequired()) {
            replication.forceResync(world, chunkKey);
            return;
        }
        int endIndex = Math.min(comparison.changes().size(), startIndex + MAX_CHANGED_CELLS_PER_BATCH);
        RegionalDiffAccumulator.SnapshotBlockReader reader =
            (x, y, z) -> snapshot.getBlockData(x & 0xF, y, z & 0xF);
        for (int index = startIndex; index < endIndex; index++) {
            SnapshotChange change = comparison.changes().get(index);
            accumulator.recordSnapshotBlockChange(world, change.worldX(), change.worldY(), change.worldZ(),
                change.data(), BlockChange.FLAG_NONE, reader, minHeight, maxHeight);
        }
        if (endIndex >= comparison.changes().size()) {
            return;
        }
        if (plugin == null) {
            applyComparisonBatch(world, worldId, chunkKey, chunkX, chunkZ, snapshot, minHeight,
                maxHeight, comparison, endIndex);
            return;
        }
        boolean scheduled = FoliaScheduler.runRegion(plugin, world, chunkX, chunkZ,
            () -> applyComparisonBatch(world, worldId, chunkKey, chunkX, chunkZ, snapshot, minHeight,
                maxHeight, comparison, endIndex), 1L);
        if (!scheduled) {
            replication.forceResync(world, chunkKey);
        }
    }

    private void resetCaptureCadence(UUID worldId, long chunkKey) {
        Map<Long, Integer> sweepMap = captureSweepCounts.get(worldId);
        if (sweepMap != null) {
            sweepMap.remove(chunkKey);
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
}
