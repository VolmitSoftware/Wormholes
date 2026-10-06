package art.arcane.optics.view;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.HashCommon;
import art.arcane.optics.math.CellKeys;

public final class WorldChangeTracker {
    public static final long AFFECTED = Long.MIN_VALUE;
    static final int CHANGE_LOG_CAPACITY = 16_384;
    private static final int MAX_TRACKED_CHUNKS_PER_WORLD = 8192;

    private final AtomicLong version = new AtomicLong();
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<Long, Long>> worldChunks = new ConcurrentHashMap<UUID, ConcurrentHashMap<Long, Long>>();
    private final ConcurrentHashMap<UUID, Long> clearFloor = new ConcurrentHashMap<UUID, Long>();
    private final ConcurrentHashMap<UUID, AtomicLong> worldMaxStamp = new ConcurrentHashMap<UUID, AtomicLong>();
    private final CopyOnWriteArrayList<ChangeListener> listeners = new CopyOnWriteArrayList<ChangeListener>();
    private final ConcurrentHashMap<UUID, ChangeLog> changeLogs = new ConcurrentHashMap<UUID, ChangeLog>();

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
        long blockKey = CellKeys.pack(blockX, blockY, blockZ);
        stamp(worldId, blockX, blockZ, blockKey, false);
        for (ChangeListener listener : listeners) {
            listener.blockChanged(worldId, blockKey);
        }
    }

    public void markChanged(UUID worldId, int blockX, int blockZ) {
        if (worldId == null) {
            return;
        }
        stamp(worldId, blockX, blockZ, chunkKey(blockX >> 4, blockZ >> 4), true);
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
                Long stamp = chunks.get(Long.valueOf(HashCommon.mix(chunkKey(cx, cz))));
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
                Long stamp = chunks.get(Long.valueOf(HashCommon.mix(key)));
                if (stamp != null && stamp.longValue() > sinceVersion) {
                    out.add(key);
                }
            }
        }
        return true;
    }

    public long unaffectedThrough(UUID worldId, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, long sinceVersion,
                                  ChangeFilter filter) {
        if (worldId == null) {
            return AFFECTED;
        }
        long current = version.get();
        ChangeLog log = changeLogs.get(worldId);
        if (log == null) {
            return current;
        }
        return log.unaffectedThrough(minChunkX, minChunkZ, maxChunkX, maxChunkZ, sinceVersion, current, filter);
    }

    public void clearWorld(UUID worldId) {
        if (worldId == null) {
            return;
        }
        ChangeLog previousLog = changeLogs.get(worldId);
        if (previousLog == null) {
            changeLogs.put(worldId, new ChangeLog(version.incrementAndGet()));
        } else {
            synchronized (previousLog) {
                changeLogs.put(worldId, new ChangeLog(version.incrementAndGet()));
            }
        }
        worldChunks.remove(worldId);
        clearFloor.remove(worldId);
        worldMaxStamp.remove(worldId);
        for (ChangeListener listener : listeners) {
            listener.worldCleared(worldId);
        }
    }

    private void stamp(UUID worldId, int blockX, int blockZ, long entryKey, boolean column) {
        while (true) {
            ChangeLog log = changeLogs.computeIfAbsent(worldId, ignored -> new ChangeLog(Long.MIN_VALUE));
            synchronized (log) {
                if (changeLogs.get(worldId) != log) {
                    continue;
                }
                ConcurrentHashMap<Long, Long> chunks = worldChunks.computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<Long, Long>(256));
                long stamp = version.incrementAndGet();
                log.append(stamp, entryKey, column);
                Long boxed = Long.valueOf(stamp);
                worldMaxStamp.computeIfAbsent(worldId, ignored -> new AtomicLong()).accumulateAndGet(stamp, Math::max);
                chunks.put(Long.valueOf(HashCommon.mix(chunkKey(blockX >> 4, blockZ >> 4))), boxed);
                if (chunks.size() > MAX_TRACKED_CHUNKS_PER_WORLD) {
                    chunks.clear();
                    clearFloor.put(worldId, boxed);
                }
                return;
            }
        }
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public interface ChangeFilter {
        boolean affectsBlock(int x, int y, int z);

        boolean affectsColumn(int chunkX, int chunkZ);
    }

    private static final class ChangeLog {
        private static final int MASK = CHANGE_LOG_CAPACITY - 1;

        private long[] stamps;
        private long[] keys;
        private boolean[] columns;
        private int next;
        private int size;
        private long droppedThrough;

        private ChangeLog(long droppedThrough) {
            this.droppedThrough = droppedThrough;
        }

        private void append(long stamp, long key, boolean column) {
            if (stamps == null) {
                stamps = new long[CHANGE_LOG_CAPACITY];
                keys = new long[CHANGE_LOG_CAPACITY];
                columns = new boolean[CHANGE_LOG_CAPACITY];
            }
            if (size == CHANGE_LOG_CAPACITY) {
                droppedThrough = stamps[next];
            } else {
                size++;
            }
            stamps[next] = stamp;
            keys[next] = key;
            columns[next] = column;
            next = (next + 1) & MASK;
        }

        private synchronized long unaffectedThrough(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
                                                    long sinceVersion, long currentVersion, ChangeFilter filter) {
            if (droppedThrough > sinceVersion) {
                return AFFECTED;
            }
            int index = next;
            for (int visited = 0; visited < size; visited++) {
                index = (index - 1) & MASK;
                long stamp = stamps[index];
                if (stamp <= sinceVersion) {
                    break;
                }
                if (stamp > currentVersion) {
                    continue;
                }
                long key = keys[index];
                if (columns[index]) {
                    int chunkX = (int) (key >> 32);
                    int chunkZ = (int) key;
                    if (inside(chunkX, chunkZ, minChunkX, minChunkZ, maxChunkX, maxChunkZ) && filter.affectsColumn(chunkX, chunkZ)) {
                        return AFFECTED;
                    }
                    continue;
                }
                int x = CellKeys.unpackX(key);
                int z = CellKeys.unpackZ(key);
                if (inside(x >> 4, z >> 4, minChunkX, minChunkZ, maxChunkX, maxChunkZ)
                    && filter.affectsBlock(x, CellKeys.unpackY(key), z)) {
                    return AFFECTED;
                }
            }
            return currentVersion;
        }

        private static boolean inside(int chunkX, int chunkZ, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
            return chunkX >= minChunkX && chunkX <= maxChunkX && chunkZ >= minChunkZ && chunkZ <= maxChunkZ;
        }
    }

    public interface ChangeListener {
        void blockChanged(UUID worldId, long blockKey);

        void columnChanged(UUID worldId, int chunkX, int chunkZ);

        void worldCleared(UUID worldId);
    }
}
