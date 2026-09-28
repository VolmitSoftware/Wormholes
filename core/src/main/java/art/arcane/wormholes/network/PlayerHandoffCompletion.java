package art.arcane.wormholes.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class PlayerHandoffCompletion {
    record Attempt(UUID transferId, UUID playerId, String peerName, long deadlineMillis) {
    }

    record Maintenance(List<Attempt> queries, List<Attempt> expired) {
    }

    private record Pending(Attempt attempt, long nextQueryMillis) {
    }

    private record Receipt(String peerName, WireMessage.HandoffResult result, long expiresAtMillis) {
    }

    private static final long QUERY_INTERVAL_MILLIS = 1_000L;
    private static final long RECEIPT_TTL_MILLIS = 120_000L;

    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Receipt> receipts = new HashMap<>();

    synchronized void dispatched(Attempt attempt, long nowMillis) {
        pending.put(attempt.transferId(), new Pending(attempt, nowMillis + QUERY_INTERVAL_MILLIS));
    }

    synchronized void abandon(UUID transferId) {
        pending.remove(transferId);
    }

    synchronized Attempt acknowledge(String peerName, WireMessage.HandoffResult result) {
        Pending entry = pending.get(result.transferId());
        if (entry == null || !entry.attempt().peerName().equals(peerName)
            || !entry.attempt().playerId().equals(result.playerId())) {
            return null;
        }
        pending.remove(result.transferId());
        return entry.attempt();
    }

    synchronized WireMessage.HandoffResult record(String peerName, WireMessage.HandoffResult result, long nowMillis) {
        Receipt existing = receipts.putIfAbsent(result.transferId(), new Receipt(peerName, result, nowMillis + RECEIPT_TTL_MILLIS));
        if (existing == null) {
            return result;
        }
        return existing.peerName().equals(peerName) && existing.result().playerId().equals(result.playerId())
            ? existing.result() : null;
    }

    synchronized WireMessage.HandoffResult receipt(String peerName, WireMessage.HandoffStatus query, long nowMillis) {
        Receipt receipt = receipts.get(query.transferId());
        if (receipt == null) {
            return null;
        }
        if (receipt.expiresAtMillis() <= nowMillis) {
            receipts.remove(query.transferId());
            return null;
        }
        return receipt.peerName().equals(peerName) && receipt.result().playerId().equals(query.playerId())
            ? receipt.result() : null;
    }

    synchronized Maintenance maintain(long nowMillis) {
        List<Attempt> queries = new ArrayList<>();
        List<Attempt> expired = new ArrayList<>();
        pending.entrySet().removeIf(entry -> {
            Pending value = entry.getValue();
            if (value.attempt().deadlineMillis() <= nowMillis) {
                expired.add(value.attempt());
                return true;
            }
            if (value.nextQueryMillis() <= nowMillis) {
                queries.add(value.attempt());
                entry.setValue(new Pending(value.attempt(), nowMillis + QUERY_INTERVAL_MILLIS));
            }
            return false;
        });
        receipts.values().removeIf(receipt -> receipt.expiresAtMillis() <= nowMillis);
        return new Maintenance(List.copyOf(queries), List.copyOf(expired));
    }

    synchronized int inFlight() {
        return pending.size();
    }

    synchronized void clear() {
        pending.clear();
        receipts.clear();
    }
}
