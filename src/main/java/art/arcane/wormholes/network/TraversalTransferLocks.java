package art.arcane.wormholes.network;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class TraversalTransferLocks {
    private static final int PRUNE_THRESHOLD = 512;

    private final Map<UUID, Long> locks = new ConcurrentHashMap<>();

    boolean isLocked(UUID entityId, long now) {
        return remaining(entityId, now) > 0L;
    }

    long remaining(UUID entityId, long now) {
        Long until = locks.get(entityId);
        if (until == null) {
            return 0L;
        }
        if (until.longValue() <= now) {
            locks.remove(entityId, until);
            return 0L;
        }
        return until.longValue() - now;
    }

    void lock(UUID entityId, long untilMillis) {
        locks.put(entityId, Long.valueOf(untilMillis));
    }

    void unlock(UUID entityId) {
        locks.remove(entityId);
    }

    void prune(long now) {
        if (locks.size() < PRUNE_THRESHOLD) {
            return;
        }
        locks.values().removeIf(until -> until.longValue() <= now);
    }

    void clear() {
        locks.clear();
    }
}
