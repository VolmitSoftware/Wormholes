package art.arcane.wormholes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.rtp.RtpRimRenderer;
import art.arcane.wormholes.render.EntityRenderLocalOcclusionArbiter;
import art.arcane.wormholes.render.PortalProjector;
import art.arcane.wormholes.render.PortalSkinRenderer;
import art.arcane.wormholes.render.ProjectionClaimArbiter;
import art.arcane.wormholes.util.AxisAlignedBB;

final class ProjectionInterestFrame {
    private final ProjectionInterestSet interestSet;
    private final ProjectionBudgetLedger ledger;
    private final ProjectionClaimArbiter claimArbiter;
    private final EntityRenderLocalOcclusionArbiter localEntityOcclusion;
    private final PortalSkinRenderer skinRenderer;
    private final RtpRimRenderer rtpRimRenderer;
    private final Supplier<ProjectionManager.RtpProjectionProvider> rtpProjectionProvider;
    private final BooleanSupplier alive;

    ProjectionInterestFrame(ProjectionInterestSet interestSet,
                            ProjectionBudgetLedger ledger,
                            ProjectionClaimArbiter claimArbiter,
                            EntityRenderLocalOcclusionArbiter localEntityOcclusion,
                            PortalSkinRenderer skinRenderer,
                            RtpRimRenderer rtpRimRenderer,
                            Supplier<ProjectionManager.RtpProjectionProvider> rtpProjectionProvider,
                            BooleanSupplier alive) {
        this.interestSet = interestSet;
        this.ledger = ledger;
        this.claimArbiter = claimArbiter;
        this.localEntityOcclusion = localEntityOcclusion;
        this.skinRenderer = skinRenderer;
        this.rtpRimRenderer = rtpRimRenderer;
        this.rtpProjectionProvider = rtpProjectionProvider;
        this.alive = alive;
    }

    void project(Player observer,
                 PortalCandidateSnapshot active,
                 AtomicInteger remainingProjectors,
                 int reservedBudget,
                 boolean updateBlocks,
                 boolean updateEntities,
                 long frameTick,
                 boolean skinWork,
                 PortalCandidateSnapshot skinSnapshot) {
        if (!observer.isOnline()) {
            remainingProjectors.addAndGet(reservedBudget);
            return;
        }
        List<PortalProjector> projected = new ArrayList<PortalProjector>();
        claimArbiter.beginFrame(observer, observer.getWorld(), false);
        localEntityOcclusion.beginFrame(observer);
        try {
            if (skinWork) {
                skinRenderer.reconcile(observer, skinSnapshot);
            }
            projectWithinFrame(observer, active, remainingProjectors, reservedBudget,
                updateBlocks, updateEntities, frameTick, projected);
        } finally {
            try {
                localEntityOcclusion.flushFrame(observer);
            } finally {
                try {
                    claimArbiter.flushFrame(observer);
                } finally {
                    for (PortalProjector projector : projected) {
                        projector.finishBlackoutDisplayFrame();
                    }
                }
            }
        }
    }

    private void projectWithinFrame(Player observer,
                                    PortalCandidateSnapshot active,
                                    AtomicInteger remainingProjectors,
                                    int reservedBudget,
                                    boolean updateBlocks,
                                    boolean updateEntities,
                                    long frameTick,
                                    List<PortalProjector> projected) {
        UUID observerId = observer.getUniqueId();
        World observerWorld = observer.getWorld();
        Location observerLocation = observer.getLocation();
        Location eye = observer.getEyeLocation();
        claimArbiter.retryPending(observer, observerWorld);
        List<ILocalPortal> interested = new ArrayList<ILocalPortal>();
        Map<UUID, PortalProjector.RtpProjectionTarget> rtpTargets = null;
        ProjectionManager.RtpProjectionProvider provider = rtpProjectionProvider.get();
        for (ILocalPortal portal : active.candidates(observerWorld, observerLocation)) {
            Location center = portal.getCenter();
            if (center == null || center.getWorld() == null) {
                continue;
            }
            if (!observerWorld.equals(center.getWorld())) {
                continue;
            }
            AxisAlignedBB view = portal.getView();
            if (view == null || !view.contains(observerLocation)) {
                continue;
            }
            ProjectionManager.ProjectionResolution resolution =
                ProjectionManager.resolveProjection(provider, portal, observer, rtpRimRenderer);
            if (!resolution.projectable()) {
                continue;
            }
            if (resolution.target() != null) {
                if (rtpTargets == null) {
                    rtpTargets = new HashMap<UUID, PortalProjector.RtpProjectionTarget>(4);
                }
                rtpTargets.put(portal.getId(), resolution.target());
            }
            boolean liveInterest = ProjectionManager.isObserverProjectionInterested(eye, center, portal);
            if (!liveInterest && !interestSet.isInsideGrace(portal.getId(), observerId, frameTick)) {
                continue;
            }
            if (liveInterest) {
                interestSet.refreshGrace(portal.getId(), observerId, frameTick);
                if (Wormholes.arrivalWarmer != null) {
                    Wormholes.arrivalWarmer.warmDestinationOf(portal);
                }
            }
            interested.add(portal);
            ledger.recordInterested();
        }
        Map<UUID, PortalProjector.RtpProjectionTarget> resolvedRtpTargets = rtpTargets == null ? Map.of() : rtpTargets;
        interested.sort(Comparator.comparingDouble(portal -> distanceSquared(eye, portal)));
        Set<UUID> interestedIds = new HashSet<UUID>(interested.size());
        for (ILocalPortal portal : interested) {
            interestedIds.add(portal.getId());
        }
        interestSet.closeUnplanned(observerId, interestedIds);
        interestSet.setRtpTargets(observerId, resolvedRtpTargets);
        if ((!updateBlocks && !updateEntities) || interested.isEmpty()) {
            remainingProjectors.addAndGet(reservedBudget);
            return;
        }
        int desiredBlocks = updateBlocks
            ? Math.min(Settings.PROJECTION_MAX_PORTALS_PER_OBSERVER_TICK, interested.size())
            : 0;
        int reservedUsed = Math.min(reservedBudget, desiredBlocks);
        int claimedBlocks = reservedUsed + ProjectionBudgetLedger.claim(remainingProjectors, desiredBlocks - reservedUsed);
        if (reservedBudget > reservedUsed) {
            remainingProjectors.addAndGet(reservedBudget - reservedUsed);
        }
        boolean observerUpdatesBlocks = claimedBlocks > 0;
        int limit = observerUpdatesBlocks ? claimedBlocks : (updateEntities ? interested.size() : 0);
        if (limit == 0) {
            ledger.recordDeferred(interested.size());
            return;
        }
        List<ILocalPortal> scheduledPortals = observerUpdatesBlocks
            ? interestSet.nextSlice(observerId, interested, limit)
            : interested;
        int deferred = Math.max(0, interested.size() - scheduledPortals.size());
        if (observerUpdatesBlocks) {
            ledger.recordScheduled(scheduledPortals.size());
            ledger.recordDeferred(deferred);
        }
        projectActiveObserver(observer, scheduledPortals, resolvedRtpTargets, observerUpdatesBlocks, updateEntities,
            projected);
    }

    private void projectActiveObserver(Player observer, List<ILocalPortal> scheduledPortals,
                                       Map<UUID, PortalProjector.RtpProjectionTarget> rtpTargets,
                                       boolean updateBlocks, boolean updateEntities,
                                       List<PortalProjector> projected) {
        if (!alive.getAsBoolean() || observer == null || !observer.isOnline()) {
            return;
        }
        for (ILocalPortal portal : scheduledPortals) {
            PortalProjector.RtpProjectionTarget rtpTarget = rtpTargets.get(portal.getId());
            if (!isPortalStillProjectable(portal, rtpTarget != null)) {
                continue;
            }
            PortalProjector projector = interestSet.obtain(portal, observer);
            if (projector == null) {
                continue;
            }

            projector.setRtpProjectionTarget(rtpTarget);
            try {
                projector.project(updateBlocks, updateEntities);
                projected.add(projector);
            } catch (Throwable ex) {
                Wormholes.instance.getLogger().log(Level.WARNING,
                        "[ProjectionManager] projection error portal=" + portal.getName() + " observer=" + observer.getName(), ex);
            } finally {
                interestSet.refreshProjectedEntities(projector);
            }
        }
    }

    private static boolean isPortalStillProjectable(ILocalPortal portal, boolean rtp) {
        if (portal == null || !portal.supportsProjections() || !portal.isProjecting() || !portal.isOpen() || portal.blocksProjection()) {
            return false;
        }
        if (!rtp && !portal.isMirrorMode() && !portal.hasTunnel()) {
            return false;
        }
        return true;
    }

    private static double distanceSquared(Location eye, ILocalPortal portal) {
        Location center = portal.getCenter();
        if (center == null || eye == null || center.getWorld() == null || eye.getWorld() == null || !center.getWorld().equals(eye.getWorld())) {
            return Double.MAX_VALUE;
        }
        return center.distanceSquared(eye);
    }
}
