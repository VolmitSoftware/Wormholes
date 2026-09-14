package art.arcane.wormholes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import org.bukkit.entity.Player;

import art.arcane.wormholes.portal.ILocalPortal;

final class ProjectionBudgetLedger {
    private static final long DIAGNOSTIC_INTERVAL_MS = 5_000L;
    private static final long OBSERVER_COST_DECAY_DIVISOR = 64L;

    private final Map<UUID, ObserverCost> observerCosts = new ConcurrentHashMap<UUID, ObserverCost>();
    private final LongSupplier nanoTime;
    private final AtomicInteger lastInterestedObservers = new AtomicInteger();
    private final AtomicInteger lastObserverCandidates = new AtomicInteger();
    private final AtomicInteger lastNewObserverScans = new AtomicInteger();
    private final AtomicInteger lastScheduledProjectors = new AtomicInteger();
    private final AtomicInteger lastDeferredProjectors = new AtomicInteger();
    private long lastDiagnostic;
    private int priorityObserverCursor;
    private int discoveryObserverCursor;

    ProjectionBudgetLedger() {
        this(System::nanoTime);
    }

    ProjectionBudgetLedger(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        this.lastDiagnostic = 0L;
        this.priorityObserverCursor = 0;
        this.discoveryObserverCursor = 0;
    }

    FrameBudget beginFrame(int maxFrameMicros) {
        lastInterestedObservers.set(0);
        lastObserverCandidates.set(0);
        lastNewObserverScans.set(0);
        lastScheduledProjectors.set(0);
        lastDeferredProjectors.set(0);
        return new FrameBudget(Math.max(0L, maxFrameMicros) * 1_000L);
    }

    void forgetObserver(UUID observerId) {
        observerCosts.remove(observerId);
    }

    void clearObserverCosts() {
        observerCosts.clear();
    }

    void recordInterested() {
        lastInterestedObservers.incrementAndGet();
    }

    void recordScheduled(int count) {
        lastScheduledProjectors.addAndGet(count);
    }

    void recordDeferred(int count) {
        lastDeferredProjectors.addAndGet(count);
    }

    static int claim(AtomicInteger remaining, int requested) {
        while (requested > 0) {
            int available = remaining.get();
            if (available <= 0) {
                return 0;
            }
            int claimed = Math.min(available, requested);
            if (remaining.compareAndSet(available, available - claimed)) {
                return claimed;
            }
        }
        return 0;
    }

    List<Player> selectObserverCandidates(List<Player> onlinePlayers,
                                          Set<UUID> priorityObserverIds,
                                          Set<UUID> unavailableObserverIds,
                                          long frameTick,
                                          int maxAdmissions,
                                          boolean discoveryEnabled) {
        if (onlinePlayers.isEmpty() || maxAdmissions <= 0) {
            lastObserverCandidates.set(0);
            lastNewObserverScans.set(0);
            return List.of();
        }
        Set<UUID> priorities = priorityObserverIds == null ? Set.of() : priorityObserverIds;
        Set<UUID> unavailable = unavailableObserverIds == null ? Set.of() : unavailableObserverIds;
        List<Player> priority = new ArrayList<Player>();
        List<Player> discovery = new ArrayList<Player>();
        Set<UUID> included = new HashSet<UUID>(onlinePlayers.size());
        for (Player player : onlinePlayers) {
            UUID playerId = player.getUniqueId();
            if (unavailable.contains(playerId) || !included.add(playerId)) {
                continue;
            }
            if (priorities.contains(playerId)) {
                priority.add(player);
            } else if (discoveryEnabled) {
                discovery.add(player);
            }
        }
        int capacity = Math.min(maxAdmissions, priority.size() + discovery.size());
        if (capacity <= 0) {
            lastObserverCandidates.set(0);
            lastNewObserverScans.set(0);
            return List.of();
        }

        int priorityTarget = priorityAdmissionTarget(capacity, !priority.isEmpty(), !discovery.isEmpty(), frameTick);
        int discoveryTarget = capacity - priorityTarget;
        List<Player> candidates = new ArrayList<Player>(capacity);
        int selectedPriority = appendRoundRobin(priority, priorityTarget, priorityObserverCursor, candidates);
        priorityObserverCursor = nextCursor(priorityObserverCursor, selectedPriority, priority.size());
        int selectedDiscovery = appendRoundRobin(discovery, discoveryTarget, discoveryObserverCursor, candidates);
        discoveryObserverCursor = nextCursor(discoveryObserverCursor, selectedDiscovery, discovery.size());

        int remaining = capacity - candidates.size();
        if (remaining > 0) {
            int extraPriority = appendRoundRobin(priority, Math.min(remaining, priority.size() - selectedPriority),
                priorityObserverCursor, candidates);
            priorityObserverCursor = nextCursor(priorityObserverCursor, extraPriority, priority.size());
            selectedPriority += extraPriority;
            remaining -= extraPriority;
        }
        if (remaining > 0) {
            int extraDiscovery = appendRoundRobin(discovery,
                Math.min(remaining, discovery.size() - selectedDiscovery), discoveryObserverCursor, candidates);
            discoveryObserverCursor = nextCursor(discoveryObserverCursor, extraDiscovery, discovery.size());
            selectedDiscovery += extraDiscovery;
        }

        lastNewObserverScans.set(selectedDiscovery);
        lastObserverCandidates.set(candidates.size());
        return candidates;
    }

    static int priorityAdmissionTarget(int capacity, boolean hasPriority, boolean hasDiscovery, long frameTick) {
        if (capacity <= 0 || !hasPriority) {
            return 0;
        }
        if (!hasDiscovery) {
            return capacity;
        }
        if (capacity == 1) {
            return Math.floorMod(frameTick - 1L, 4L) == 3L ? 0 : 1;
        }
        return capacity - Math.max(1, capacity / 4);
    }

    private static <T> int appendRoundRobin(List<T> source, int requested, int cursor, List<T> destination) {
        int selected = Math.min(Math.max(0, requested), source.size());
        if (selected <= 0) {
            return 0;
        }
        int start = Math.floorMod(cursor, source.size());
        for (int offset = 0; offset < selected; offset++) {
            destination.add(source.get((start + offset) % source.size()));
        }
        return selected;
    }

    private static int nextCursor(int cursor, int selected, int size) {
        if (size <= 0 || selected <= 0) {
            return cursor;
        }
        return (Math.floorMod(cursor, size) + selected) % size;
    }

    void emitDiagnostics(long tickCount, List<ILocalPortal> active, ProjectionInterestSet interestSet) {
        long now = System.currentTimeMillis();
        if (now - lastDiagnostic < DIAGNOSTIC_INTERVAL_MS) {
            return;
        }
        lastDiagnostic = now;

        int totalPortals = Wormholes.portalManager.getLocalPortals().size();
        ProjectionInterestSet.Census census = interestSet.census();

        Wormholes.v("[ProjectionManager] tick=" + tickCount + " totalPortals=" + totalPortals
                + " activeProjectingPortals=" + active.size() + " observers=" + census.observers() + " renderedBlocks=" + census.renderedBlocks()
                + " candidates=" + lastObserverCandidates.get() + " newScans=" + lastNewObserverScans.get()
                + " interested=" + lastInterestedObservers.get() + " scheduled=" + lastScheduledProjectors.get() + " deferred=" + lastDeferredProjectors.get());

        if (active.isEmpty() && totalPortals > 0) {
            for (ILocalPortal portal : Wormholes.portalManager.getLocalPortals()) {
                Wormholes.v("[ProjectionManager]   inactive portal: " + describePortal(portal));
            }
        }
    }

    private static String describePortal(ILocalPortal portal) {
        StringBuilder sb = new StringBuilder();
        sb.append(portal.getName())
                .append(" type=").append(portal.getType())
                .append(" supportsProjections=").append(portal.supportsProjections())
                .append(" isOpen=").append(portal.isOpen())
                .append(" hasTunnel=").append(portal.hasTunnel())
                .append(" center=").append(ProjectionManager.formatLoc(portal.getCenter()))
                .append(" direction=").append(portal.getDirection())
                .append(" projecting=").append(portal.isProjecting())
                .append(" mode=").append(portal.getProjectionMode())
                .append(" mirror=").append(portal.isMirrorMode());
        return sb.toString();
    }

    final class FrameBudget {
        private final long limitNanos;
        private final Map<Thread, ExecutionBudget> executionBudgets = new ConcurrentHashMap<Thread, ExecutionBudget>();

        private FrameBudget(long limitNanos) {
            this.limitNanos = limitNanos;
        }

        ObserverFrame beginObserver(UUID observerId) {
            return new ObserverFrame(observerId, this);
        }
    }

    final class ObserverFrame implements AutoCloseable {
        private final UUID observerId;
        private final ObserverCost cost;
        private final ExecutionBudget execution;
        private final boolean admitsBlocks;
        private final long startedNanos;
        private final long deadlineNanos;
        private boolean renderedBlocks;
        private boolean closed;

        private ObserverFrame(UUID observerId, FrameBudget frameBudget) {
            this.observerId = observerId;
            this.cost = observerCosts.computeIfAbsent(observerId, ignored -> new ObserverCost());
            this.execution = frameBudget.executionBudgets.computeIfAbsent(Thread.currentThread(), ignored -> new ExecutionBudget());
            long estimate = cost.peakNanos > 0L ? cost.peakNanos : frameBudget.limitNanos;
            this.admitsBlocks = frameBudget.limitNanos == 0L || !execution.startedBlocks
                || estimate <= Math.max(0L, frameBudget.limitNanos - execution.spentNanos);
            this.startedNanos = nanoTime.getAsLong();
            long remainingNanos = Math.max(0L, frameBudget.limitNanos - execution.spentNanos);
            this.deadlineNanos = frameBudget.limitNanos == 0L || startedNanos > Long.MAX_VALUE - remainingNanos
                ? Long.MAX_VALUE : startedNanos + remainingNanos;
        }

        boolean admitsBlocks() {
            return admitsBlocks;
        }

        long deadlineNanos() {
            return deadlineNanos;
        }

        void recordBlockWork() {
            renderedBlocks = true;
            execution.startedBlocks = true;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            long elapsedNanos = Math.max(0L, nanoTime.getAsLong() - startedNanos);
            execution.spentNanos += Math.min(elapsedNanos, Long.MAX_VALUE - execution.spentNanos);
            if (renderedBlocks && observerCosts.get(observerId) == cost) {
                long decayedPeak = cost.peakNanos - cost.peakNanos / OBSERVER_COST_DECAY_DIVISOR;
                cost.peakNanos = Math.max(elapsedNanos, decayedPeak);
            }
        }
    }

    private static final class ObserverCost {
        private volatile long peakNanos;
    }

    private static final class ExecutionBudget {
        private long spentNanos;
        private boolean startedBlocks;
    }
}
