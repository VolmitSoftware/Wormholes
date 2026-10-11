package art.arcane.wormholes.render;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Consumer;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;

import it.unimi.dsi.fastutil.longs.LongSet;
import art.arcane.optics.light.LightOverlay;

final class ProjectionBlockUpdateRepair {
    static final int BLOCK_ENTITIES = 1;
    static final int BIOMES = 2;
    static final int LIGHT = 4;
    static final int REPLACED = 8;
    static final int BASELINE_CHANGED = 16;
    static final int ALL_FIDELITY = BLOCK_ENTITIES | BIOMES | LIGHT;
    private final ConcurrentHashMap<Long, Integer> claimedIds = new ConcurrentHashMap<Long, Integer>();
    private final ConcurrentHashMap<Long, Integer> ownBlockChanges = new ConcurrentHashMap<Long, Integer>();
    private final ConcurrentHashMap<Long, Boolean> pending = new ConcurrentHashMap<Long, Boolean>();
    private final ConcurrentHashMap<Long, FidelityOverwrite> fidelityChunks = new ConcurrentHashMap<Long, FidelityOverwrite>();
    private final ConcurrentHashMap<Long, Boolean> blockEntities = new ConcurrentHashMap<Long, Boolean>();
    private final ConcurrentHashMap<Long, Consumer<Boolean>> writeReceipts = new ConcurrentHashMap<Long, Consumer<Boolean>>();
    private final ConcurrentLinkedQueue<Runnable> completions = new ConcurrentLinkedQueue<Runnable>();
    private final AtomicLong lifecycle = new AtomicLong();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final Predicate<Runnable> scheduler;
    private final Runnable repair;
    private volatile boolean closed;
    private volatile boolean confirmedWrites;
    private volatile boolean biomes;
    private volatile boolean light;

    ProjectionBlockUpdateRepair(Predicate<Runnable> scheduler, Runnable repair) {
        this.scheduler = scheduler;
        this.repair = repair;
    }

    void confirmWrites(boolean enabled) {
        confirmedWrites = enabled;
    }

    boolean confirmsWrites() {
        return confirmedWrites;
    }

    long lifecycle() {
        return lifecycle.get();
    }

    void receipt(long key, Consumer<Boolean> completion) {
        writeReceipts.put(Long.valueOf(key), completion);
    }

    Consumer<Boolean> takeReceipt(long key) {
        return writeReceipts.remove(Long.valueOf(key));
    }

    boolean hasReceipt(long key) {
        return writeReceipts.containsKey(Long.valueOf(key));
    }

    Consumer<Boolean> takeReceipts(long[] keys) {
        List<Consumer<Boolean>> callbacks = new ArrayList<Consumer<Boolean>>();
        for (long key : keys) {
            Consumer<Boolean> callback = takeReceipt(key);
            if (callback != null) {
                callbacks.add(callback);
            }
        }
        return success -> {
            for (Consumer<Boolean> callback : callbacks) {
                callback.accept(success);
            }
        };
    }

    void completed(Runnable completion) {
        if (!closed) {
            completions.add(completion);
            schedule();
        }
    }

    void drainCompletions() {
        Runnable completion;
        while ((completion = completions.poll()) != null) {
            completion.run();
        }
    }

    void trackBiomes(boolean enabled) {
        biomes = enabled;
    }

    void trackLight(boolean enabled) {
        light = enabled;
    }

    boolean tracksLight() {
        return light;
    }

    boolean tracksBiomes() {
        return biomes;
    }

    boolean claimed(long key) {
        return !closed && claimedIds.containsKey(Long.valueOf(key));
    }

    void claim(long key, int globalId) {
        if (!closed) {
            claimedIds.put(Long.valueOf(key), Integer.valueOf(globalId));
        }
    }

    void release(long key) {
        claimedIds.remove(Long.valueOf(key));
        ownBlockChanges.remove(Long.valueOf(key));
        pending.remove(Long.valueOf(key));
    }

    void expectBukkitBlockChange(long key) {
        if (!closed && (claimedIds.containsKey(Long.valueOf(key)) || hasReceipt(key))) {
            ownBlockChanges.merge(Long.valueOf(key), Integer.valueOf(1), Integer::sum);
        }
    }

    boolean touchesClaim(long key, int globalId) {
        Integer expected = claimedIds.get(Long.valueOf(key));
        return !closed && expected != null && (expected.intValue() != globalId || ownBlockChanges.containsKey(Long.valueOf(key)));
    }

    boolean consumeBukkitBlockChange(long key) {
        if (!ownBlockChanges.containsKey(Long.valueOf(key))) {
            return false;
        }
        AtomicBoolean consumed = new AtomicBoolean();
        ownBlockChanges.computeIfPresent(Long.valueOf(key), (ignored, count) -> {
            consumed.set(true);
            return count.intValue() <= 1 ? null : Integer.valueOf(count.intValue() - 1);
        });
        return consumed.get();
    }

    void overwritten(long[] keys) {
        if (closed) {
            return;
        }
        for (long key : keys) {
            if (claimedIds.containsKey(Long.valueOf(key))) {
                pending.put(Long.valueOf(key), Boolean.TRUE);
            }
        }
        schedule();
    }

    void overwriteChunk(long chunkKey, int channels) {
        overwriteChunk(chunkKey, channels, null, null);
    }

    void overwriteChunk(long chunkKey, int channels, int[][] biomes, LightOverlay.ChunkLight light) {
        if (closed) {
            return;
        }
        fidelityChunks.merge(Long.valueOf(chunkKey), new FidelityOverwrite(channels, biomes, light), (previous, next) -> {
            LightOverlay.ChunkLight mergedLight = next.light();
            if ((next.channels() & REPLACED) == 0) {
                if (mergedLight == null) {
                    mergedLight = previous.light();
                } else if (previous.light() != null) {
                    mergedLight = LightOverlay.mergeBaselines(previous.light(), mergedLight);
                }
            }
            return new FidelityOverwrite(previous.channels() | next.channels(),
                next.biomes() == null ? previous.biomes() : next.biomes(), mergedLight);
        });
        schedule();
    }

    void overwriteBlockEntity(long key) {
        if (!closed) {
            blockEntities.put(Long.valueOf(key), Boolean.TRUE);
            schedule();
        }
    }

    void drainFidelity(Long2IntMap chunks, LongSet cells, Long2ObjectMap<int[][]> biomes,
                       Long2ObjectMap<LightOverlay.ChunkLight> lights) {
        for (Long key : fidelityChunks.keySet()) {
            FidelityOverwrite overwritten = fidelityChunks.remove(key);
            if (overwritten != null) {
                chunks.put(key.longValue(), overwritten.channels());
                if (overwritten.biomes() != null) {
                    biomes.put(key.longValue(), overwritten.biomes());
                }
                if (overwritten.light() != null) {
                    lights.put(key.longValue(), overwritten.light());
                }
            }
        }
        for (Long key : blockEntities.keySet()) {
            if (blockEntities.remove(key) != null) {
                cells.add(key.longValue());
            }
        }
    }

    private void schedule() {
        if ((!pending.isEmpty() || !fidelityChunks.isEmpty() || !blockEntities.isEmpty() || !completions.isEmpty())
            && scheduled.compareAndSet(false, true)) {
            boolean accepted = false;
            try {
                accepted = scheduler.test(this::runRepair);
            } finally {
                if (!accepted) {
                    scheduled.set(false);
                }
            }
        }
    }

    void drain(LongSet keys) {
        for (Long key : pending.keySet()) {
            if (pending.remove(key) != null && claimedIds.containsKey(key)) {
                keys.add(key.longValue());
            }
        }
    }

    void reset() {
        lifecycle.incrementAndGet();
        ownBlockChanges.clear();
        pending.clear();
        fidelityChunks.clear();
        blockEntities.clear();
        writeReceipts.clear();
        completions.clear();
    }

    void close() {
        closed = true;
        claimedIds.clear();
        reset();
    }

    private void runRepair() {
        scheduled.set(false);
        if (!closed) {
            repair.run();
        }
    }
    private record FidelityOverwrite(int channels, int[][] biomes, LightOverlay.ChunkLight light) {
    }

}
