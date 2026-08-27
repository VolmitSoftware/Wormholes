package art.arcane.wormholes.render;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;

public final class EntityRenderLocalOcclusionArbiter {
    private final Map<UUID, ObserverState> observers;
    private final VisibilityController visibility;

    public EntityRenderLocalOcclusionArbiter() {
        this(new BukkitVisibilityController());
    }

    EntityRenderLocalOcclusionArbiter(VisibilityController visibility) {
        this.observers = new ConcurrentHashMap<UUID, ObserverState>();
        this.visibility = visibility;
    }

    public void beginFrame(Player observer) {
        if (observer == null || !observer.isOnline()) {
            return;
        }
        ObserverState state = observers.computeIfAbsent(observer.getUniqueId(), ignored -> new ObserverState());
        synchronized (state) {
            state.frameOpen = true;
        }
    }

    public void flushFrame(Player observer) {
        if (observer == null) {
            return;
        }
        UUID observerId = observer.getUniqueId();
        if (!observer.isOnline()) {
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

    void replace(Player observer, UUID ownerId, Map<UUID, Entity> desired) {
        if (observer == null || ownerId == null || desired == null) {
            return;
        }
        if (!observer.isOnline()) {
            discardObserver(observer.getUniqueId());
            return;
        }
        UUID observerId = observer.getUniqueId();
        ObserverState state = observers.computeIfAbsent(observerId, ignored -> new ObserverState());
        synchronized (state) {
            if (desired.isEmpty()) {
                state.claimsByOwner.remove(ownerId);
            } else {
                state.claimsByOwner.put(ownerId, new HashMap<UUID, Entity>(desired));
            }
            if (!state.frameOpen) {
                reconcile(observer, state);
                removeIfEmpty(observerId, state);
            }
        }
    }

    void release(Player observer, UUID ownerId) {
        if (observer == null || ownerId == null) {
            return;
        }
        UUID observerId = observer.getUniqueId();
        ObserverState state = observers.get(observerId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            state.claimsByOwner.remove(ownerId);
            if (!observer.isOnline()) {
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
            for (Map<UUID, Entity> claims : state.claimsByOwner.values()) {
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

    private void reconcile(Player observer, ObserverState state) {
        Map<UUID, Entity> desired = new HashMap<UUID, Entity>();
        for (Map<UUID, Entity> claims : state.claimsByOwner.values()) {
            for (Map.Entry<UUID, Entity> claim : claims.entrySet()) {
                Entity entity = claim.getValue();
                if (entity != null && entity.isValid() && !entity.isDead()) {
                    desired.put(claim.getKey(), entity);
                }
            }
        }

        Iterator<Map.Entry<UUID, Entity>> hidden = state.appliedHidden.entrySet().iterator();
        while (hidden.hasNext()) {
            Map.Entry<UUID, Entity> entry = hidden.next();
            if (desired.containsKey(entry.getKey())) {
                entry.setValue(desired.get(entry.getKey()));
                continue;
            }
            Entity entity = entry.getValue();
            try {
                if (entity != null && entity.isValid() && !entity.isDead()) {
                    visibility.show(observer, entity);
                }
                hidden.remove();
            } catch (IllegalStateException error) {
                reportOwnershipFailure(state, error);
                scheduleRetry(observer, state);
            }
        }

        for (Map.Entry<UUID, Entity> entry : desired.entrySet()) {
            Entity entity = entry.getValue();
            if (state.appliedHidden.containsKey(entry.getKey())) {
                state.appliedHidden.put(entry.getKey(), entity);
                continue;
            }
            try {
                visibility.hide(observer, entity);
                state.appliedHidden.put(entry.getKey(), entity);
            } catch (IllegalStateException error) {
                reportOwnershipFailure(state, error);
                scheduleRetry(observer, state);
            }
        }
    }

    private void scheduleRetry(Player observer, ObserverState state) {
        Wormholes plugin = Wormholes.instance;
        if (plugin == null || observer == null || !observer.isOnline()
            || !state.retryScheduled.compareAndSet(false, true)) {
            return;
        }
        boolean scheduled = FoliaScheduler.runEntity(plugin, observer, () -> {
            state.retryScheduled.set(false);
            UUID observerId = observer.getUniqueId();
            if (observers.get(observerId) != state || !observer.isOnline()) {
                return;
            }
            synchronized (state) {
                if (!state.frameOpen) {
                    reconcile(observer, state);
                    removeIfEmpty(observerId, state);
                }
            }
        }, 1L);
        if (!scheduled) {
            state.retryScheduled.set(false);
        }
    }

    private void removeIfEmpty(UUID observerId, ObserverState state) {
        if (!state.frameOpen && state.claimsByOwner.isEmpty() && state.appliedHidden.isEmpty()) {
            observers.remove(observerId, state);
        }
    }

    private static void reportOwnershipFailure(ObserverState state, IllegalStateException error) {
        if (state.ownershipWarningSent) {
            return;
        }
        state.ownershipWarningSent = true;
        Wormholes plugin = Wormholes.instance;
        Logger logger = plugin == null ? Logger.getLogger("Wormholes") : plugin.getLogger();
        logger.log(Level.WARNING,
            "[spoof] Local entity occlusion crossed an unowned Folia region; this projection will retain its prior visibility state.",
            error);
    }

    interface VisibilityController {
        void hide(Player observer, Entity entity);

        void show(Player observer, Entity entity);
    }

    private static final class BukkitVisibilityController implements VisibilityController {
        @Override
        public void hide(Player observer, Entity entity) {
            observer.hideEntity(Wormholes.instance, entity);
        }

        @Override
        public void show(Player observer, Entity entity) {
            observer.showEntity(Wormholes.instance, entity);
        }
    }

    private static final class ObserverState {
        private final Map<UUID, Map<UUID, Entity>> claimsByOwner;
        private final Map<UUID, Entity> appliedHidden;
        private final AtomicBoolean retryScheduled;
        private boolean frameOpen;
        private boolean ownershipWarningSent;

        private ObserverState() {
            this.claimsByOwner = new HashMap<UUID, Map<UUID, Entity>>(4);
            this.appliedHidden = new HashMap<UUID, Entity>(16);
            this.retryScheduled = new AtomicBoolean(false);
            this.frameOpen = false;
            this.ownershipWarningSent = false;
        }
    }
}
