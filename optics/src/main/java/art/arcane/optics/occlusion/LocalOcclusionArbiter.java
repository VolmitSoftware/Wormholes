package art.arcane.optics.occlusion;

import java.util.HashMap;
import java.util.Objects;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import art.arcane.optics.entity.EntityFeed;
import art.arcane.optics.entity.EntityOutput;
import art.arcane.optics.spi.OpticsScheduler;

public final class LocalOcclusionArbiter<O, E> {
    private final Map<UUID, ObserverState> observers;
    private final EntityFeed<O, ?, ?, E> feed;
    private final EntityOutput<O, ?, ?, ?, E> visibility;
    private final OpticsScheduler<O, ?> scheduler;

    public LocalOcclusionArbiter(EntityFeed<O, ?, ?, E> feed, EntityOutput<O, ?, ?, ?, E> visibility, OpticsScheduler<O, ?> scheduler) {
        this.observers = new ConcurrentHashMap<UUID, ObserverState>();
        this.feed = feed;
        this.visibility = visibility;
        this.scheduler = Objects.requireNonNull(scheduler);
    }

    public void beginFrame(O observer) {
        if (observer == null || !visibility.online(observer)) {
            return;
        }
        ObserverState state = observers.computeIfAbsent(visibility.id(observer), ignored -> new ObserverState());
        synchronized (state) {
            state.frameOpen = true;
        }
    }

    public void flushFrame(O observer) {
        if (observer == null) {
            return;
        }
        UUID observerId = visibility.id(observer);
        if (!visibility.online(observer)) {
            discardObserver(observerId);
            return;
        }
        ObserverState state = observers.get(observerId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            state.frameOpen = false;
            reconcile(observer, state);
            removeIfEmpty(observerId, state);
        }
    }

    public void replace(O observer, UUID ownerId, Map<UUID, E> desired) {
        if (observer == null || ownerId == null || desired == null) {
            return;
        }
        if (!visibility.online(observer)) {
            discardObserver(visibility.id(observer));
            return;
        }
        UUID observerId = visibility.id(observer);
        ObserverState state = observers.computeIfAbsent(observerId, ignored -> new ObserverState());
        synchronized (state) {
            if (desired.isEmpty()) {
                state.claimsByOwner.remove(ownerId);
            } else {
                state.claimsByOwner.put(ownerId, new HashMap<UUID, E>(desired));
            }
            if (!state.frameOpen) {
                reconcile(observer, state);
                removeIfEmpty(observerId, state);
            }
        }
    }

    public void release(O observer, UUID ownerId) {
        if (observer == null || ownerId == null) {
            return;
        }
        UUID observerId = visibility.id(observer);
        ObserverState state = observers.get(observerId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            state.claimsByOwner.remove(ownerId);
            if (!visibility.online(observer)) {
                state.appliedHidden.clear();
                removeIfEmpty(observerId, state);
                return;
            }
            if (!state.frameOpen) {
                reconcile(observer, state);
                removeIfEmpty(observerId, state);
            }
        }
    }

    public boolean isClaimed(UUID observerId, UUID entityId) {
        if (observerId == null || entityId == null) {
            return false;
        }
        ObserverState state = observers.get(observerId);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            for (Map<UUID, E> claims : state.claimsByOwner.values()) {
                if (claims.containsKey(entityId)) {
                    return true;
                }
            }
            return false;
        }
    }

    public void discardObserver(UUID observerId) {
        if (observerId == null) {
            return;
        }
        ObserverState state = observers.remove(observerId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            state.claimsByOwner.clear();
            state.appliedHidden.clear();
            state.frameOpen = false;
        }
    }

    public void clear() {
        observers.clear();
    }

    private void reconcile(O observer, ObserverState state) {
        Map<UUID, E> desired = new HashMap<UUID, E>();
        for (Map<UUID, E> claims : state.claimsByOwner.values()) {
            for (Map.Entry<UUID, E> claim : claims.entrySet()) {
                E entity = claim.getValue();
                if (entity != null && feed.valid(entity)) {
                    desired.put(claim.getKey(), entity);
                }
            }
        }

        Iterator<Map.Entry<UUID, E>> hidden = state.appliedHidden.entrySet().iterator();
        while (hidden.hasNext()) {
            Map.Entry<UUID, E> entry = hidden.next();
            if (desired.containsKey(entry.getKey())) {
                entry.setValue(desired.get(entry.getKey()));
                continue;
            }
            E entity = entry.getValue();
            try {
                if (entity != null && feed.valid(entity)) {
                    visibility.showLocal(observer, entity);
                }
                hidden.remove();
            } catch (IllegalStateException error) {
                reportOwnershipFailure(observer, state, error);
                scheduleRetry(observer, state);
            }
        }

        for (Map.Entry<UUID, E> entry : desired.entrySet()) {
            E entity = entry.getValue();
            if (state.appliedHidden.containsKey(entry.getKey())) {
                state.appliedHidden.put(entry.getKey(), entity);
                continue;
            }
            try {
                visibility.hideLocal(observer, entity);
                state.appliedHidden.put(entry.getKey(), entity);
            } catch (IllegalStateException error) {
                reportOwnershipFailure(observer, state, error);
                scheduleRetry(observer, state);
            }
        }
    }

    private void scheduleRetry(O observer, ObserverState state) {
        if (observer == null || !visibility.online(observer)
            || !state.retryScheduled.compareAndSet(false, true)) {
            return;
        }
        boolean scheduled = scheduler.runForObserver(observer, () -> {
            state.retryScheduled.set(false);
            UUID observerId = visibility.id(observer);
            if (observers.get(observerId) != state || !visibility.online(observer)) {
                return;
            }
            synchronized (state) {
                if (!state.frameOpen) {
                    reconcile(observer, state);
                    removeIfEmpty(observerId, state);
                }
            }
        });
        if (!scheduled) {
            state.retryScheduled.set(false);
        }
    }

    private void removeIfEmpty(UUID observerId, ObserverState state) {
        if (!state.frameOpen && state.claimsByOwner.isEmpty() && state.appliedHidden.isEmpty()) {
            observers.remove(observerId, state);
        }
    }

    private void reportOwnershipFailure(O observer, ObserverState state, IllegalStateException error) {
        if (!state.ownershipWarningSent) {
            state.ownershipWarningSent = true;
            visibility.warning(observer,
                "local entity occlusion crossed an unowned region; the projection keeps its prior visibility state", error);
        }
    }

    private final class ObserverState {
        private final Map<UUID, Map<UUID, E>> claimsByOwner;
        private final Map<UUID, E> appliedHidden;
        private final AtomicBoolean retryScheduled;
        private boolean frameOpen;
        private boolean ownershipWarningSent;

        private ObserverState() {
            this.claimsByOwner = new HashMap<UUID, Map<UUID, E>>(4);
            this.appliedHidden = new HashMap<UUID, E>(16);
            this.retryScheduled = new AtomicBoolean(false);
            this.frameOpen = false;
            this.ownershipWarningSent = false;
        }
    }
}
