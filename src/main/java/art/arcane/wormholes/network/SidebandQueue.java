package art.arcane.wormholes.network;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

final class SidebandQueue {
    private static final long OUTBOX_MAX_BYTES = 5L * 1024L * 1024L;
    private static final long CONTROL_RESERVE_BYTES = 512L * 1024L;

    private final NetworkManager network;
    private final Logger logger;
    private final Map<String, SidebandOutbox> outboxes = new ConcurrentHashMap<>();
    private final Map<String, Integer> requestBudgets = new ConcurrentHashMap<>();
    private final AtomicLong retiredDroppedBytes = new AtomicLong();
    private final AtomicLong retiredDroppedCount = new AtomicLong();

    SidebandQueue(NetworkManager network, Logger logger) {
        this.network = network;
        this.logger = logger;
    }

    boolean enqueue(String peerName, OutboundFrame frame) {
        List<MinecraftStatusBridge.EncodedMessage> queuedMessages = encode(peerName, frame);
        if (queuedMessages.isEmpty()) {
            return false;
        }
        SidebandOutbox outbox = outboxFor(peerName);
        if (outbox.offer(queuedMessages)) {
            return true;
        }
        if (SidebandOutbox.tierOf(queuedMessages.get(0)) != SidebandOutbox.TIER_BEST_EFFORT) {
            network.dialer().setLastError(peerName, "game-port status sideband outbox is full");
        }
        return false;
    }

    private List<MinecraftStatusBridge.EncodedMessage> encode(String peerName, OutboundFrame frame) {
        try {
            byte[] probe = frame.plainFrame();
            if (probe.length <= MinecraftStatusBridge.MAX_FRAME_BYTES) {
                return List.of(new MinecraftStatusBridge.EncodedMessage(frame.message(), probe));
            }
            return network.fragmenter().fragment(peerName, frame.message(), probe);
        } catch (IOException e) {
            logger.warning("net: could not encode " + frame.message().type() + " for status sideband to " + peerName + ": " + e.getMessage());
            return List.of();
        }
    }

    SidebandOutbox.DrainBatch drain(String peerName, int budgetBytes) {
        return outboxFor(peerName).drain(budgetBytes, MinecraftStatusBridge.MAX_MESSAGES);
    }

    boolean pending(String peerName) {
        SidebandOutbox outbox = outboxes.get(peerName);
        return outbox != null && !outbox.isEmpty();
    }

    long queuedBytes(String peerName) {
        SidebandOutbox outbox = outboxes.get(peerName);
        return outbox == null ? 0L : outbox.queuedBytes();
    }

    long queuedCount(String peerName) {
        SidebandOutbox outbox = outboxes.get(peerName);
        return outbox == null ? 0L : outbox.queuedCount();
    }

    long droppedBytes(String peerName) {
        SidebandOutbox outbox = outboxes.get(peerName);
        return outbox == null ? 0L : outbox.droppedBytes();
    }

    long droppedCount(String peerName) {
        SidebandOutbox outbox = outboxes.get(peerName);
        return outbox == null ? 0L : outbox.droppedCount();
    }

    int budgetFor(String peerName) {
        return requestBudgets.getOrDefault(peerName, NetworkManager.STATUS_BRIDGE_REQUEST_BUDGET_MIN_BYTES);
    }

    void recordSuccess(String peerName) {
        requestBudgets.put(peerName, Math.min(NetworkManager.STATUS_BRIDGE_REQUEST_BUDGET_MAX_BYTES, budgetFor(peerName) * 2));
    }

    void recordFailure(String peerName) {
        requestBudgets.put(peerName, Math.max(NetworkManager.STATUS_BRIDGE_REQUEST_BUDGET_MIN_BYTES, budgetFor(peerName) / 2));
    }

    void forget(String peerName) {
        SidebandOutbox outbox = outboxes.remove(peerName);
        if (outbox != null) {
            outbox.discardAll();
            retire(outbox);
        }
        requestBudgets.remove(peerName);
    }

    void clear() {
        for (SidebandOutbox outbox : outboxes.values()) {
            outbox.discardAll();
            retire(outbox);
        }
        outboxes.clear();
        requestBudgets.clear();
    }

    Totals totals() {
        long queuedBytes = 0L;
        long queuedCount = 0L;
        long droppedBytes = retiredDroppedBytes.get();
        long droppedCount = retiredDroppedCount.get();
        for (SidebandOutbox outbox : outboxes.values()) {
            queuedBytes += outbox.queuedBytes();
            queuedCount += outbox.queuedCount();
            droppedBytes += outbox.droppedBytes();
            droppedCount += outbox.droppedCount();
        }
        return new Totals(queuedBytes, queuedCount, droppedBytes, droppedCount);
    }

    static boolean isLatencyCritical(WireMessage message) {
        WireMessageType type = message instanceof WireMessage.Routed routed ? routed.innerType() : message.type();
        return switch (type) {
            case HANDOFF_REQUEST, HANDOFF_ACK, HANDOFF_DENY, HANDOFF_CANCEL,
                 ENTITY_TRANSFER, ENTITY_TRANSFER_ACK, VIEW_SUBSCRIBE, VIEW_UNSUBSCRIBE, VIEW_BULK_COMPLETE -> true;
            default -> false;
        };
    }

    private SidebandOutbox outboxFor(String peerName) {
        return outboxes.computeIfAbsent(peerName, ignored -> new SidebandOutbox(OUTBOX_MAX_BYTES, CONTROL_RESERVE_BYTES));
    }

    private void retire(SidebandOutbox outbox) {
        retiredDroppedBytes.addAndGet(outbox.droppedBytes());
        retiredDroppedCount.addAndGet(outbox.droppedCount());
    }

    record Totals(long queuedBytes, long queuedCount, long droppedBytes, long droppedCount) {
    }
}
