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

    ProjectionBudgetLedger() {
        this.lastDiagnostic = 0L;
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

    List<Player> selectObserverCandidates(List<Player> onlinePlayers, ProjectionInterestSet interestSet,
                                          long frameTick, int maxNewScans) {
        if (onlinePlayers.isEmpty()) {
            lastObserverCandidates.set(0);
            return List.of();
        }
        Set<UUID> trackedObserverIds = interestSet.observerIds();

        int discoveryLimit = Math.min(Math.max(0, maxNewScans), onlinePlayers.size());
        List<Player> candidates = new ArrayList<Player>(Math.min(onlinePlayers.size(), trackedObserverIds.size() + discoveryLimit));
        Set<UUID> included = new HashSet<UUID>(trackedObserverIds.size() + discoveryLimit);
        for (Player player : onlinePlayers) {
            UUID playerId = player.getUniqueId();
            if (trackedObserverIds.contains(playerId) && included.add(playerId)) {
                candidates.add(player);
            }
        }

        int start = ProjectionManager.observerDiscoveryStart(onlinePlayers.size(), discoveryLimit, frameTick);
        int discovered = 0;
        for (int offset = 0; offset < onlinePlayers.size() && discovered < discoveryLimit; offset++) {
            Player player = onlinePlayers.get((start + offset) % onlinePlayers.size());
            if (!included.add(player.getUniqueId())) {
                continue;
            }
            candidates.add(player);
            discovered++;
        }
        lastNewObserverScans.set(discovered);
        lastObserverCandidates.set(candidates.size());
        return candidates;
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
