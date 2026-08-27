package art.arcane.wormholes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import org.bukkit.entity.Player;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.render.EntityRenderLocalOcclusionArbiter;
import art.arcane.wormholes.render.PortalProjector;
import art.arcane.wormholes.render.ProjectionClaimArbiter;
import art.arcane.wormholes.render.view.ProjectionWorldViewProvider;

final class ProjectionInterestSet {
    private final ProjectionClaimArbiter claimArbiter;
    private final EntityRenderLocalOcclusionArbiter localEntityOcclusion;
    private final ProjectionWorldViewProvider viewProvider;
    private final ProjectionInterestCloseQueue closeQueue;
    private final BooleanSupplier alive;
    private final Map<UUID, Map<UUID, PortalProjector>> projectors;
    private final Map<UUID, Map<UUID, Long>> interestGraceUntil;
    private final Map<UUID, Integer> observerPortalCursors;
    private final ProjectedEntityInterestIndex<PortalProjector> projectedEntityInterests;

    ProjectionInterestSet(ProjectionClaimArbiter claimArbiter,
                          EntityRenderLocalOcclusionArbiter localEntityOcclusion,
                          ProjectionWorldViewProvider viewProvider,
                          ProjectionInterestCloseQueue closeQueue,
                          BooleanSupplier alive) {
        this.claimArbiter = claimArbiter;
        this.localEntityOcclusion = localEntityOcclusion;
        this.viewProvider = viewProvider;
        this.closeQueue = closeQueue;
        this.alive = alive;
        this.projectors = new ConcurrentHashMap<UUID, Map<UUID, PortalProjector>>();
        this.interestGraceUntil = new ConcurrentHashMap<UUID, Map<UUID, Long>>();
        this.observerPortalCursors = new ConcurrentHashMap<UUID, Integer>();
        this.projectedEntityInterests = new ProjectedEntityInterestIndex<PortalProjector>();
    }

    boolean isEmpty() {
        return projectors.isEmpty();
    }

    PortalProjector obtain(ILocalPortal portal, Player observer) {
        Map<UUID, PortalProjector> portalProjectors = projectors.get(portal.getId());
        if (portalProjectors == null) {
            portalProjectors = new ConcurrentHashMap<UUID, PortalProjector>();
            Map<UUID, PortalProjector> existing = projectors.putIfAbsent(portal.getId(), portalProjectors);
            if (existing != null) {
                portalProjectors = existing;
            }
        }

        UUID activeObserverId = observer.getUniqueId();
        PortalProjector projector = portalProjectors.get(activeObserverId);
        if (projector == null) {
            if (!alive.getAsBoolean()) {
                return null;
            }
            projector = new PortalProjector(portal, observer, claimArbiter, viewProvider, alive,
                localEntityOcclusion);
            portalProjectors.put(activeObserverId, projector);
            projectedEntityInterests.activate(projector);
            Wormholes.v("[ProjectionManager] new projector portal=" + portal.getName()
                    + " observer=" + observer.getName()
                    + " portalCenter=" + ProjectionManager.formatLoc(portal.getCenter())
                    + " observerLoc=" + ProjectionManager.formatLoc(observer.getLocation()));
        }
        return projector;
    }

    void setRtpTargets(UUID observerId, Map<UUID, PortalProjector.RtpProjectionTarget> rtpTargets) {
        for (Map.Entry<UUID, PortalProjector.RtpProjectionTarget> entry : rtpTargets.entrySet()) {
            Map<UUID, PortalProjector> portalProjectors = projectors.get(entry.getKey());
            if (portalProjectors == null) {
                continue;
            }
            PortalProjector projector = portalProjectors.get(observerId);
            if (projector != null) {
                projector.setRtpProjectionTarget(entry.getValue());
            }
        }
    }

    void closeUnplanned(UUID observerId, Set<UUID> interestedPortalIds) {
        for (Map.Entry<UUID, Map<UUID, PortalProjector>> entry : projectors.entrySet()) {
            if (interestedPortalIds.contains(entry.getKey())) {
                continue;
            }
            PortalProjector projector = entry.getValue().remove(observerId);
            if (projector == null) {
                continue;
            }
            projectedEntityInterests.deactivate(projector);
            projector.close();
            if (entry.getValue().isEmpty()) {
                projectors.remove(entry.getKey(), entry.getValue());
            }
        }
        if (interestedPortalIds.isEmpty()) {
            observerPortalCursors.remove(observerId);
        }
    }

    void retainPortals(List<ILocalPortal> activePortals) {
        Set<UUID> activeIds = new HashSet<UUID>(activePortals.size());
        for (ILocalPortal portal : activePortals) {
            activeIds.add(portal.getId());
        }
        for (Map.Entry<UUID, Map<UUID, PortalProjector>> entry : projectors.entrySet()) {
            if (activeIds.contains(entry.getKey()) || !projectors.remove(entry.getKey(), entry.getValue())) {
                continue;
            }
            interestGraceUntil.remove(entry.getKey());
            for (PortalProjector projector : entry.getValue().values()) {
                projectedEntityInterests.deactivate(projector);
                closeQueue.close(projector);
            }
        }
    }

    void retirePortal(UUID portalId) {
        Map<UUID, PortalProjector> portalProjectors = projectors.remove(portalId);
        interestGraceUntil.remove(portalId);
        if (portalProjectors == null) {
            return;
        }
        for (PortalProjector projector : portalProjectors.values()) {
            projectedEntityInterests.deactivate(projector);
            closeQueue.close(projector);
        }
    }

    void retire(UUID portalId, UUID observerId) {
        Map<UUID, PortalProjector> portalProjectors = projectors.get(portalId);
        if (portalProjectors == null) {
            return;
        }
        PortalProjector projector = portalProjectors.remove(observerId);
        if (projector == null) {
            return;
        }
        projectedEntityInterests.deactivate(projector);
        closeQueue.close(projector);
    }

    void closeObserver(UUID observerId) {
        for (Map<UUID, PortalProjector> portalProjectors : projectors.values()) {
            PortalProjector projector = portalProjectors.remove(observerId);
            if (projector == null) {
                continue;
            }
            projectedEntityInterests.deactivate(projector);
            closeQueue.close(projector);
        }
    }

    void discardObserver(UUID observerId) {
        for (Map<UUID, PortalProjector> portalProjectors : projectors.values()) {
            PortalProjector projector = portalProjectors.remove(observerId);
            if (projector != null) {
                projectedEntityInterests.deactivate(projector);
                projector.discard();
            }
        }
    }

    void refreshProjectedEntities(PortalProjector projector) {
        if (projector == null || projector.getObserver() == null) {
            projectedEntityInterests.deactivate(projector);
            return;
        }
        Map<UUID, PortalProjector> portalProjectors = projectors.get(projector.getPortal().getId());
        UUID observerId = projector.getObserver().getUniqueId();
        if (projector.isClosed() || portalProjectors == null || portalProjectors.get(observerId) != projector) {
            projectedEntityInterests.deactivate(projector);
            return;
        }
        projectedEntityInterests.replace(projector, projector.getProjectedEntityIds());
    }

    List<Player> projectedEntityObservers(UUID entityId) {
        Map<UUID, Player> observers = new LinkedHashMap<UUID, Player>();
        for (PortalProjector projector : projectedEntityInterests.targets(entityId)) {
            Player observer = projector.getObserver();
            if (observer == null || projector.isClosed()) {
                continue;
            }
            observers.putIfAbsent(observer.getUniqueId(), observer);
        }
        return new ArrayList<Player>(observers.values());
    }

    List<PortalProjector> projectedEntityProjectors(UUID entityId, UUID observerId) {
        if (entityId == null || observerId == null) {
            return List.of();
        }
        List<PortalProjector> interested = new ArrayList<PortalProjector>();
        for (PortalProjector projector : projectedEntityInterests.targets(entityId)) {
            Player observer = projector.getObserver();
            if (observer == null || projector.isClosed() || !observerId.equals(observer.getUniqueId())) {
                continue;
            }
            interested.add(projector);
        }
        return interested;
    }

    void forgetObserver(UUID observerId) {
        observerPortalCursors.remove(observerId);
        for (Map.Entry<UUID, Map<UUID, Long>> graceEntry : interestGraceUntil.entrySet()) {
            Map<UUID, Long> byObserver = graceEntry.getValue();
            byObserver.remove(observerId);
            if (byObserver.isEmpty()) {
                interestGraceUntil.remove(graceEntry.getKey(), byObserver);
            }
        }
    }

    Set<UUID> observerIds() {
        Set<UUID> observerIds = new HashSet<UUID>();
        for (Map<UUID, PortalProjector> portalProjectors : projectors.values()) {
            observerIds.addAll(portalProjectors.keySet());
        }
        return observerIds;
    }

    List<PortalProjector> snapshot() {
        List<PortalProjector> snapshot = new ArrayList<PortalProjector>();
        for (Map<UUID, PortalProjector> portalProjectors : projectors.values()) {
            snapshot.addAll(portalProjectors.values());
        }
        return snapshot;
    }

    int countSpoofedEntities() {
        int total = 0;
        for (Map<UUID, PortalProjector> portalProjectors : projectors.values()) {
            for (PortalProjector projector : portalProjectors.values()) {
                total += projector.getSpoofedEntityCount();
            }
        }
        return total;
    }

    Census census() {
        int totalObservers = 0;
        int totalRendered = 0;
        for (Map<UUID, PortalProjector> portalProjectors : projectors.values()) {
            totalObservers += portalProjectors.size();
            for (PortalProjector projector : portalProjectors.values()) {
                totalRendered += projector.getProjectedCount();
            }
        }
        return new Census(totalObservers, totalRendered);
    }

    void invalidateProjectionReuse() {
        for (Map<UUID, PortalProjector> portalProjectors : projectors.values()) {
            for (PortalProjector projector : portalProjectors.values()) {
                projector.invalidateProjectionReuse();
            }
        }
    }

    boolean isInsideGrace(UUID portalId, UUID observerId, long frameTick) {
        Map<UUID, PortalProjector> portalProjectors = projectors.get(portalId);
        if (portalProjectors == null || !portalProjectors.containsKey(observerId)) {
            return false;
        }
        Map<UUID, Long> byObserver = interestGraceUntil.get(portalId);
        if (byObserver == null) {
            return false;
        }
        Long until = byObserver.get(observerId);
        return until != null && until.longValue() >= frameTick;
    }

    void refreshGrace(UUID portalId, UUID observerId, long frameTick) {
        int graceTicks = Math.max(0, Settings.PROJECTION_INTEREST_GRACE_TICKS);
        if (graceTicks <= 0) {
            return;
        }
        Map<UUID, Long> byObserver = interestGraceUntil.get(portalId);
        if (byObserver == null) {
            byObserver = new ConcurrentHashMap<UUID, Long>(4);
            Map<UUID, Long> existing = interestGraceUntil.putIfAbsent(portalId, byObserver);
            if (existing != null) {
                byObserver = existing;
            }
        }
        byObserver.put(observerId, Long.valueOf(frameTick + graceTicks));
    }

    void pruneGrace(long frameTick) {
        for (Map.Entry<UUID, Map<UUID, Long>> portalEntry : interestGraceUntil.entrySet()) {
            Map<UUID, Long> byObserver = portalEntry.getValue();
            for (Map.Entry<UUID, Long> observerEntry : byObserver.entrySet()) {
                if (observerEntry.getValue().longValue() < frameTick) {
                    byObserver.remove(observerEntry.getKey(), observerEntry.getValue());
                }
            }
            if (byObserver.isEmpty()) {
                interestGraceUntil.remove(portalEntry.getKey(), byObserver);
            }
        }
    }

    List<ILocalPortal> nextSlice(UUID observerId, List<ILocalPortal> interested, int limit) {
        int cursor = observerPortalCursors.getOrDefault(observerId, Integer.valueOf(0)).intValue();
        List<ILocalPortal> scheduledPortals = ProjectionManager.selectRoundRobin(interested, limit, cursor);
        observerPortalCursors.put(observerId, Integer.valueOf((cursor + scheduledPortals.size()) % interested.size()));
        return scheduledPortals;
    }

    void clear() {
        projectedEntityInterests.close();
        projectors.clear();
        interestGraceUntil.clear();
        observerPortalCursors.clear();
    }

    record Census(int observers, int renderedBlocks) {
    }
}
