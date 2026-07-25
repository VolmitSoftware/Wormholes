package art.arcane.wormholes.network;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

final class TraversalEntityTransferLedger {
    enum ClaimStatus {
        STARTED,
        IN_FLIGHT,
        APPLIED
    }

    record Claim(ClaimStatus status, long token) {
    }

    private enum TransferStatus {
        IN_FLIGHT,
        APPLIED
    }

    private record Entry(long token, TransferStatus status, long updatedAtMillis) {
    }

    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong nextToken = new AtomicLong();

    Claim claim(UUID transferId, long nowMillis) {
        long token = nextToken.incrementAndGet();
        Entry fresh = new Entry(token, TransferStatus.IN_FLIGHT, nowMillis);
        Entry existing = entries.putIfAbsent(transferId, fresh);
        if (existing == null) {
            return new Claim(ClaimStatus.STARTED, token);
        }
        ClaimStatus status = existing.status() == TransferStatus.APPLIED ? ClaimStatus.APPLIED : ClaimStatus.IN_FLIGHT;
        return new Claim(status, existing.token());
    }

    boolean markApplied(UUID transferId, Claim claim, long nowMillis) {
        if (claim == null || claim.status() != ClaimStatus.STARTED) {
            return false;
        }
        Entry[] changed = new Entry[1];
        entries.computeIfPresent(transferId, (ignored, current) -> {
            if (current.token() != claim.token() || current.status() != TransferStatus.IN_FLIGHT) {
                return current;
            }
            Entry applied = new Entry(current.token(), TransferStatus.APPLIED, nowMillis);
            changed[0] = applied;
            return applied;
        });
        return changed[0] != null;
    }

    boolean release(UUID transferId, Claim claim) {
        if (claim == null || claim.status() != ClaimStatus.STARTED) {
            return false;
        }
        boolean[] removed = new boolean[1];
        entries.computeIfPresent(transferId, (ignored, current) -> {
            if (current.token() != claim.token()) {
                return current;
            }
            removed[0] = true;
            return null;
        });
        return removed[0];
    }

    void pruneApplied(long nowMillis, long ttlMillis, int minimumSize) {
        if (entries.size() < minimumSize) {
            return;
        }
        entries.entrySet().removeIf(entry -> entry.getValue().status() == TransferStatus.APPLIED
            && entry.getValue().updatedAtMillis() + ttlMillis < nowMillis);
    }
}
