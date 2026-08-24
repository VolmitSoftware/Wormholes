package art.arcane.wormholes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class ProjectedEntityInterestIndex<T> {
    private final Map<T, Set<UUID>> entitiesByTarget;
    private final Map<UUID, Set<T>> targetsByEntity;
    private final Object lifecycleLock;
    private volatile boolean closed;

    ProjectedEntityInterestIndex() {
        this.entitiesByTarget = new ConcurrentHashMap<T, Set<UUID>>();
        this.targetsByEntity = new ConcurrentHashMap<UUID, Set<T>>();
        this.lifecycleLock = new Object();
        this.closed = false;
    }

    void activate(T target) {
        T requiredTarget = Objects.requireNonNull(target, "target");
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            synchronized (requiredTarget) {
                entitiesByTarget.putIfAbsent(requiredTarget, Set.of());
            }
        }
    }

    boolean replace(T target, Set<UUID> entityIds) {
        T requiredTarget = Objects.requireNonNull(target, "target");
        Set<UUID> next = Set.copyOf(Objects.requireNonNull(entityIds, "entityIds"));
        synchronized (requiredTarget) {
            Set<UUID> previous = entitiesByTarget.get(requiredTarget);
            if (previous == null || previous.equals(next)) {
                return false;
            }
            for (UUID entityId : previous) {
                if (!next.contains(entityId)) {
                    unlink(entityId, requiredTarget);
                }
            }
            for (UUID entityId : next) {
                if (!previous.contains(entityId)) {
                    link(entityId, requiredTarget);
                }
            }
            entitiesByTarget.put(requiredTarget, next);
            return true;
        }
    }

    void deactivate(T target) {
        if (target == null) {
            return;
        }
        synchronized (target) {
            Set<UUID> previous = entitiesByTarget.remove(target);
            if (previous == null) {
                return;
            }
            for (UUID entityId : previous) {
                unlink(entityId, target);
            }
        }
    }

    List<T> targets(UUID entityId) {
        if (entityId == null) {
            return List.of();
        }
        Set<T> targets = targetsByEntity.get(entityId);
        return targets == null || targets.isEmpty() ? List.of() : new ArrayList<T>(targets);
    }

    int entityCount() {
        return targetsByEntity.size();
    }

    int targetCount() {
        return entitiesByTarget.size();
    }

    void close() {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            for (T target : new ArrayList<T>(entitiesByTarget.keySet())) {
                deactivate(target);
            }
            targetsByEntity.clear();
        }
    }

    private void link(UUID entityId, T target) {
        targetsByEntity.compute(entityId, (ignored, targets) -> {
            Set<T> activeTargets = targets;
            if (activeTargets == null) {
                activeTargets = ConcurrentHashMap.newKeySet();
            }
            activeTargets.add(target);
            return activeTargets;
        });
    }

    private void unlink(UUID entityId, T target) {
        targetsByEntity.computeIfPresent(entityId, (ignored, targets) -> {
            targets.remove(target);
            return targets.isEmpty() ? null : targets;
        });
    }
}
