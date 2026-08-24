package art.arcane.wormholes.network;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class EntityTransferAckRetryQueue {
    static final int MAX_PENDING = 256;
    static final long RETRY_INTERVAL_MILLIS = 5_000L;

    private final Map<UUID, Pending> pending = new LinkedHashMap<>();

    synchronized void track(String peerName, WireMessage.EntityTransferAck ack, long nowMillis, long ttlMillis) {
        prune(nowMillis);
        if (pending.containsKey(ack.transferId())) {
            return;
        }
        if (pending.size() >= MAX_PENDING) {
            Iterator<UUID> iterator = pending.keySet().iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
        pending.put(ack.transferId(), new Pending(
            peerName,
            ack,
            nowMillis + ttlMillis,
            nowMillis + RETRY_INTERVAL_MILLIS
        ));
    }

    synchronized List<Retry> due(long nowMillis) {
        prune(nowMillis);
        List<Retry> retries = new ArrayList<>();
        for (Map.Entry<UUID, Pending> entry : pending.entrySet()) {
            Pending current = entry.getValue();
            if (current.nextAttemptAtMillis() > nowMillis) {
                continue;
            }
            retries.add(new Retry(current.peerName(), current.ack()));
            entry.setValue(new Pending(
                current.peerName(),
                current.ack(),
                current.expiresAtMillis(),
                nowMillis + RETRY_INTERVAL_MILLIS
            ));
        }
        return retries;
    }

    synchronized void expedite(UUID transferId, long nowMillis) {
        Pending current = pending.get(transferId);
        if (current == null || current.nextAttemptAtMillis() <= nowMillis) {
            return;
        }
        pending.put(transferId, new Pending(
            current.peerName(),
            current.ack(),
            current.expiresAtMillis(),
            nowMillis
        ));
    }

    synchronized void clear() {
        pending.clear();
    }

    private void prune(long nowMillis) {
        pending.values().removeIf(retry -> retry.expiresAtMillis() <= nowMillis);
    }

    record Retry(String peerName, WireMessage.EntityTransferAck ack) {
    }

    private record Pending(String peerName, WireMessage.EntityTransferAck ack, long expiresAtMillis,
                           long nextAttemptAtMillis) {
    }
}
