package art.arcane.wormholes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

import org.bukkit.entity.Player;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.render.PortalProjector;

final class ProjectionInterestCloseQueue {
    private final Map<PortalProjector, CloseTaskState> closingProjectors = new ConcurrentHashMap<PortalProjector, CloseTaskState>();
    private final BooleanSupplier alive;

    ProjectionInterestCloseQueue(BooleanSupplier alive) {
        this.alive = alive;
    }

    boolean isEmpty() {
        return closingProjectors.isEmpty();
    }

    Set<PortalProjector> pending() {
        return closingProjectors.keySet();
    }

    int countSpoofedEntities() {
        int total = 0;
        for (PortalProjector projector : closingProjectors.keySet()) {
            total += projector.getSpoofedEntityCount();
        }
        return total;
    }

    void close(PortalProjector projector) {
        close(projector, () -> {
        });
    }

    void close(PortalProjector projector, Runnable onComplete) {
        CloseTaskState state = closingProjectors.computeIfAbsent(projector, CloseTaskState::new);
        state.addCompletion(onComplete);
        attemptClose(state);
    }

    void retryPending() {
        for (CloseTaskState state : closingProjectors.values()) {
            if (state.isPending()) {
                attemptClose(state);
            }
        }
    }

    void forceDiscardAll() {
        for (CloseTaskState state : closingProjectors.values()) {
            state.projector.requestDiscard();
            state.projector.discard();
            completeClose(state);
        }
    }

    private void attemptClose(CloseTaskState state) {
        if (!state.trySchedule()) {
            return;
        }
        PortalProjector projector = state.projector;
        Player observer = projector.getObserver();
        if (observer == null || !observer.isOnline()) {
            projector.discard();
            completeClose(state);
            return;
        }
        boolean scheduled;
        try {
            scheduled = FoliaScheduler.runEntity(Wormholes.instance, observer, () -> {
                try {
                    if (observer.isOnline()) {
                        projector.close();
                    } else {
                        projector.discard();
                    }
                } finally {
                    completeClose(state);
                }
            }, 0L, () -> retireClose(state));
        } catch (RuntimeException error) {
            scheduled = false;
            Wormholes.instance.getLogger().log(Level.WARNING, "Unable to schedule projection cleanup for "
                + observer.getUniqueId(), error);
        }
        if (!scheduled) {
            state.markRejected();
            if (!alive.getAsBoolean()) {
                projector.discard();
                completeClose(state);
            }
        }
    }

    private void retireClose(CloseTaskState state) {
        state.markRejected();
        Player observer = state.projector.getObserver();
        if (!alive.getAsBoolean() || observer == null || !observer.isOnline()) {
            state.projector.discard();
            completeClose(state);
        }
    }

    private void completeClose(CloseTaskState state) {
        List<Runnable> completions = state.complete();
        if (completions == null) {
            return;
        }
        closingProjectors.remove(state.projector, state);
        for (Runnable completion : completions) {
            completion.run();
        }
    }

    private static final class CloseTaskState {
        private final PortalProjector projector;
        private final List<Runnable> completions = new ArrayList<Runnable>(1);
        private boolean scheduled;
        private boolean completed;

        private CloseTaskState(PortalProjector projector) {
            this.projector = projector;
        }

        private void addCompletion(Runnable completion) {
            boolean runNow;
            synchronized (this) {
                runNow = completed;
                if (!runNow) {
                    completions.add(completion);
                }
            }
            if (runNow) {
                completion.run();
            }
        }

        private synchronized boolean trySchedule() {
            if (completed || scheduled) {
                return false;
            }
            scheduled = true;
            return true;
        }

        private synchronized void markRejected() {
            if (!completed) {
                scheduled = false;
            }
        }

        private synchronized boolean isPending() {
            return !completed && !scheduled;
        }

        private synchronized List<Runnable> complete() {
            if (completed) {
                return null;
            }
            completed = true;
            scheduled = false;
            List<Runnable> result = new ArrayList<Runnable>(completions);
            completions.clear();
            return result;
        }
    }
}
