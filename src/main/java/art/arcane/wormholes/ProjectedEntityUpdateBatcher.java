package art.arcane.wormholes;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation.EntityAnimationType;

final class ProjectedEntityUpdateBatcher {
    static final int MAX_ENTITIES_PER_DRAIN = 256;

    private final Map<UUID, ObserverQueue> queues;
    private final AtomicLong generations;
    private volatile boolean closed;

    ProjectedEntityUpdateBatcher() {
        this.queues = new ConcurrentHashMap<UUID, ObserverQueue>();
        this.generations = new AtomicLong();
        this.closed = false;
    }

    ScheduleLease offerAnimation(UUID observerId, UUID entityId, EntityAnimationType type) {
        if (closed || observerId == null || entityId == null || type == null) {
            return null;
        }
        ObserverQueue queue = queueForOffer(observerId);
        if (queue == null) {
            return null;
        }
        ScheduleLease lease = queue.offer(entityId, Action.animation(type))
            ? new ScheduleLease(observerId, queue.generation())
            : null;
        return retainOfferedLease(observerId, queue, lease);
    }

    ScheduleLease offerHurt(UUID observerId, UUID entityId, float yaw) {
        if (closed || observerId == null || entityId == null) {
            return null;
        }
        ObserverQueue queue = queueForOffer(observerId);
        if (queue == null) {
            return null;
        }
        ScheduleLease lease = queue.offer(entityId, Action.hurt(yaw))
            ? new ScheduleLease(observerId, queue.generation())
            : null;
        return retainOfferedLease(observerId, queue, lease);
    }

    Batch drain(ScheduleLease lease) {
        ObserverQueue queue = queue(lease);
        return queue == null ? Batch.empty() : queue.drain(MAX_ENTITIES_PER_DRAIN);
    }

    Completion complete(ScheduleLease lease) {
        ObserverQueue queue = queue(lease);
        return queue == null ? Completion.finished() : queue.complete();
    }

    void reject(ScheduleLease lease) {
        ObserverQueue queue = queue(lease);
        if (queue != null) {
            queue.reject();
        }
    }

    void discard(ScheduleLease lease) {
        ObserverQueue queue = queue(lease);
        if (queue != null && queues.remove(lease.observerId(), queue)) {
            queue.reject();
        }
    }

    void discard(UUID observerId) {
        ObserverQueue queue = queues.remove(observerId);
        if (queue != null) {
            queue.reject();
        }
    }

    int pendingEntityCount(UUID observerId) {
        ObserverQueue queue = queues.get(observerId);
        return queue == null ? 0 : queue.pendingEntityCount();
    }

    void close() {
        closed = true;
        for (ObserverQueue queue : queues.values()) {
            queue.reject();
        }
        queues.clear();
    }

    private ObserverQueue queue(ScheduleLease lease) {
        if (lease == null) {
            return null;
        }
        ObserverQueue queue = queues.get(lease.observerId());
        return queue != null && queue.generation() == lease.generation() ? queue : null;
    }

    private ObserverQueue queueForOffer(UUID observerId) {
        return queues.compute(observerId, (ignored, existing) -> {
            if (closed) {
                return null;
            }
            return existing == null ? new ObserverQueue(generations.incrementAndGet()) : existing;
        });
    }

    private ScheduleLease retainOfferedLease(UUID observerId, ObserverQueue queue, ScheduleLease lease) {
        if (!closed) {
            return lease;
        }
        queues.remove(observerId, queue);
        queue.reject();
        return null;
    }

    enum ActionKind {
        ANIMATION,
        HURT
    }

    record Action(ActionKind kind, EntityAnimationType animationType, float yaw) {
        Action {
            Objects.requireNonNull(kind, "kind");
            if (kind == ActionKind.ANIMATION) {
                Objects.requireNonNull(animationType, "animationType");
            }
        }

        static Action animation(EntityAnimationType type) {
            return new Action(ActionKind.ANIMATION, Objects.requireNonNull(type, "type"), 0.0F);
        }

        static Action hurt(float yaw) {
            return new Action(ActionKind.HURT, null, yaw);
        }
    }

    record Batch(Map<UUID, List<Action>> updates) {
        Batch {
            updates = Map.copyOf(Objects.requireNonNull(updates, "updates"));
        }

        static Batch empty() {
            return new Batch(Map.of());
        }

        boolean isEmpty() {
            return updates.isEmpty();
        }
    }

    record Completion(boolean reschedule, UUID representativeEntityId) {
        static Completion finished() {
            return new Completion(false, null);
        }
    }

    record ScheduleLease(UUID observerId, long generation) {
        ScheduleLease {
            Objects.requireNonNull(observerId, "observerId");
        }
    }

    private static final class ObserverQueue {
        private final Map<UUID, PendingEntityUpdate> pending;
        private final long generation;
        private boolean scheduled;

        private ObserverQueue(long generation) {
            this.pending = new LinkedHashMap<UUID, PendingEntityUpdate>();
            this.generation = generation;
            this.scheduled = false;
        }

        private long generation() {
            return generation;
        }

        private synchronized boolean offer(UUID entityId, Action action) {
            pending.computeIfAbsent(entityId, ignored -> new PendingEntityUpdate()).offer(action);
            if (scheduled) {
                return false;
            }
            scheduled = true;
            return true;
        }

        private synchronized Batch drain(int limit) {
            if (pending.isEmpty()) {
                return Batch.empty();
            }
            Map<UUID, List<Action>> drained = new LinkedHashMap<UUID, List<Action>>(Math.min(limit, pending.size()));
            Iterator<Map.Entry<UUID, PendingEntityUpdate>> iterator = pending.entrySet().iterator();
            while (iterator.hasNext() && drained.size() < limit) {
                Map.Entry<UUID, PendingEntityUpdate> entry = iterator.next();
                drained.put(entry.getKey(), entry.getValue().snapshot());
                iterator.remove();
            }
            return new Batch(drained);
        }

        private synchronized Completion complete() {
            if (pending.isEmpty()) {
                scheduled = false;
                return Completion.finished();
            }
            return new Completion(true, pending.keySet().iterator().next());
        }

        private synchronized void reject() {
            pending.clear();
            scheduled = false;
        }

        private synchronized int pendingEntityCount() {
            return pending.size();
        }
    }

    private static final class PendingEntityUpdate {
        private static final ActionKey HURT_KEY = new ActionKey(ActionKind.HURT, null);

        private final Map<ActionKey, Action> actions;

        private PendingEntityUpdate() {
            this.actions = new LinkedHashMap<ActionKey, Action>();
        }

        private void offer(Action action) {
            ActionKey key = action.kind() == ActionKind.HURT
                ? HURT_KEY
                : new ActionKey(ActionKind.ANIMATION, action.animationType());
            actions.put(key, action);
        }

        private List<Action> snapshot() {
            return new ArrayList<Action>(actions.values());
        }
    }

    private record ActionKey(ActionKind kind, EntityAnimationType animationType) {
    }
}
