package art.arcane.wormholes.render;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import it.unimi.dsi.fastutil.longs.LongCollection;

public final class ProjectionWorldChangeTracker {
    private static final int MAX_TRACKED_CHUNKS_PER_WORLD = 8192;

    private final AtomicLong version = new AtomicLong();
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<Long, Long>> worldChunks = new ConcurrentHashMap<UUID, ConcurrentHashMap<Long, Long>>();
    private final ConcurrentHashMap<UUID, Long> clearFloor = new ConcurrentHashMap<UUID, Long>();
    private final ConcurrentHashMap<UUID, AtomicLong> worldMaxStamp = new ConcurrentHashMap<UUID, AtomicLong>();
    private final CopyOnWriteArrayList<ChangeListener> listeners = new CopyOnWriteArrayList<ChangeListener>();

    public long currentVersion() {
        return version.get();
    }

    public void addListener(ChangeListener listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(ChangeListener listener) {
        listeners.remove(listener);
    }

    public void markChanged(UUID worldId, int blockX, int blockY, int blockZ) {
        if (worldId == null) {
            return;
        }
        stamp(worldId, blockX, blockZ);
        long blockKey = ProjectionCellKey.pack(blockX, blockY, blockZ);
        for (ChangeListener listener : listeners) {
            listener.blockChanged(worldId, blockKey);
        }
    }

    public void markChanged(UUID worldId, int blockX, int blockZ) {
        if (worldId == null) {
            return;
        }
        stamp(worldId, blockX, blockZ);
        for (ChangeListener listener : listeners) {
            listener.columnChanged(worldId, blockX >> 4, blockZ >> 4);
        }
    }

    public boolean dirtySince(UUID worldId, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, long sinceVersion) {
        if (worldId == null) {
            return true;
        }
        Long floor = clearFloor.get(worldId);
        if (floor != null && floor.longValue() > sinceVersion) {
            return true;
        }
        AtomicLong maxStamp = worldMaxStamp.get(worldId);
        if (maxStamp == null || maxStamp.get() <= sinceVersion) {
            return false;
        }
        ConcurrentHashMap<Long, Long> chunks = worldChunks.get(worldId);
        if (chunks == null || chunks.isEmpty()) {
            return false;
        }
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                Long stamp = chunks.get(Long.valueOf(chunkKey(cx, cz)));
                if (stamp != null && stamp.longValue() > sinceVersion) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean collectDirtySince(UUID worldId, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, long sinceVersion,
                                     LongCollection out) {
        if (worldId == null) {
            return false;
        }
        Long floor = clearFloor.get(worldId);
        if (floor != null && floor.longValue() > sinceVersion) {
            return false;
        }
        AtomicLong maxStamp = worldMaxStamp.get(worldId);
        if (maxStamp == null || maxStamp.get() <= sinceVersion) {
            return true;
        }
        ConcurrentHashMap<Long, Long> chunks = worldChunks.get(worldId);
        if (chunks == null || chunks.isEmpty()) {
            return true;
        }
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                long key = chunkKey(cx, cz);
                Long stamp = chunks.get(Long.valueOf(key));
                if (stamp != null && stamp.longValue() > sinceVersion) {
                    out.add(key);
                }
            }
        }
        return true;
    }

    public void clearWorld(UUID worldId) {
        if (worldId == null) {
            return;
        }
        worldChunks.remove(worldId);
        clearFloor.remove(worldId);
        worldMaxStamp.remove(worldId);
        for (ChangeListener listener : listeners) {
            listener.worldCleared(worldId);
        }
    }

    private void stamp(UUID worldId, int blockX, int blockZ) {
        ConcurrentHashMap<Long, Long> chunks = worldChunks.computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<Long, Long>(256));
        long stamp = version.incrementAndGet();
        Long boxed = Long.valueOf(stamp);
        worldMaxStamp.computeIfAbsent(worldId, ignored -> new AtomicLong()).accumulateAndGet(stamp, Math::max);
        chunks.put(Long.valueOf(chunkKey(blockX >> 4, blockZ >> 4)), boxed);
        if (chunks.size() > MAX_TRACKED_CHUNKS_PER_WORLD) {
            chunks.clear();
            clearFloor.put(worldId, boxed);
        }
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public interface ChangeListener {
        void blockChanged(UUID worldId, long blockKey);

        void columnChanged(UUID worldId, int chunkX, int chunkZ);

        void worldCleared(UUID worldId);
    }
}
