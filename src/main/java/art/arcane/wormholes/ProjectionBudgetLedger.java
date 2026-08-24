package art.arcane.wormholes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.entity.Player;

import art.arcane.wormholes.portal.ILocalPortal;

final class ProjectionBudgetLedger {
    private static final long DIAGNOSTIC_INTERVAL_MS = 5_000L;

    private final AtomicInteger lastInterestedObservers = new AtomicInteger();
    private final AtomicInteger lastObserverCandidates = new AtomicInteger();
    private final AtomicInteger lastNewObserverScans = new AtomicInteger();
    private final AtomicInteger lastScheduledProjectors = new AtomicInteger();
    private final AtomicInteger lastDeferredProjectors = new AtomicInteger();
    private long lastDiagnostic;
    private int priorityObserverCursor;
    private int discoveryObserverCursor;

    ProjectionBudgetLedger() {
        this.lastDiagnostic = 0L;
        this.priorityObserverCursor = 0;
        this.discoveryObserverCursor = 0;
    }

    void beginFrame() {
        lastInterestedObservers.set(0);
        lastObserverCandidates.set(0);
        lastNewObserverScans.set(0);
        lastScheduledProjectors.set(0);
        lastDeferredProjectors.set(0);
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
}
