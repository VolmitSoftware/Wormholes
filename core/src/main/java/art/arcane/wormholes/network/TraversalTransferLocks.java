package art.arcane.wormholes.network;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class TraversalTransferLocks {
    private static final int PRUNE_THRESHOLD = 512;

    private final Map<UUID, Lease> locks = new ConcurrentHashMap<>();

    boolean isLocked(UUID entityId, long now) {
        return remaining(entityId, now) > 0L;
    }

    long remaining(UUID entityId, long now) {
        Lease lease = locks.get(entityId);
        if (lease == null) {
            return 0L;
        }
        if (lease.untilMillis() <= now) {
            locks.remove(entityId, lease);
            return 0L;
        }
        return lease.untilMillis() - now;
    }

    void lock(UUID entityId, long untilMillis) {
        locks.put(entityId, new Lease(null, untilMillis));
    }

    void lockTransfer(UUID playerId, UUID transferId, long untilMillis) {
        locks.put(playerId, new Lease(Objects.requireNonNull(transferId), untilMillis));
    }

    boolean ownsTransfer(UUID playerId, UUID transferId) {
        Lease lease = locks.get(playerId);
        return lease != null && transferId.equals(lease.transferId());
    }

    boolean renewTransfer(UUID playerId, UUID transferId, long untilMillis) {
        Lease lease = locks.get(playerId);
        return lease != null && transferId.equals(lease.transferId())
            && locks.replace(playerId, lease, new Lease(transferId, untilMillis));
    }

    boolean unlockTransfer(UUID playerId, UUID transferId) {
        Lease lease = locks.get(playerId);
        return lease != null && transferId.equals(lease.transferId()) && locks.remove(playerId, lease);
    }

    void unlock(UUID entityId) {
        locks.remove(entityId);
    }

    void prune(long now) {
        if (locks.size() < PRUNE_THRESHOLD) {
            return;
        }
        locks.values().removeIf(lease -> lease.untilMillis() <= now);
    }

    void clear() {
        locks.clear();
    }

    private record Lease(UUID transferId, long untilMillis) {
    }
}
