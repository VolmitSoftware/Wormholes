package art.arcane.wormholes;

import org.bukkit.entity.Entity;
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
import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.wormholes.render.PortalProjector;
import art.arcane.wormholes.render.PortalSkinRenderer;
import art.arcane.wormholes.render.ProjectionClaimArbiter;
import art.arcane.wormholes.util.AxisAlignedBB;

final class ProjectionInterestFrame {
    private final ProjectionInterestSet interestSet;
    private final ProjectionBudgetLedger ledger;
    private final ProjectionClaimArbiter claimArbiter;
    private final EntityRenderLocalOcclusionArbiter<Player, Entity> localEntityOcclusion;
    private final PortalSkinRenderer skinRenderer;
    private final RtpRimRenderer rtpRimRenderer;
    private final Supplier<ProjectionManager.RtpProjectionProvider> rtpProjectionProvider;
    private final BooleanSupplier alive;

    ProjectionInterestFrame(ProjectionInterestSet interestSet,
                            ProjectionBudgetLedger ledger,
                            ProjectionClaimArbiter claimArbiter,
                            EntityRenderLocalOcclusionArbiter<Player, Entity> localEntityOcclusion,
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
                 PortalCandidateSnapshot skinSnapshot,
                 ProjectionBudgetLedger.FrameBudget frameBudget) {
        if (!observer.isOnline()) {
            remainingProjectors.addAndGet(reservedBudget);
            return;
        }
        try (ProjectionBudgetLedger.ObserverFrame observerBudget = frameBudget.beginObserver(observer.getUniqueId())) {
            List<PortalProjector> projected = new ArrayList<PortalProjector>();
            claimArbiter.beginFrame(observer, observer.getWorld(), false);
            localEntityOcclusion.beginFrame(observer);
            try {
                if (skinWork) {
                    skinRenderer.reconcile(observer, skinSnapshot);
                }
                projectWithinFrame(observer, active, remainingProjectors, reservedBudget,
                    updateBlocks, updateEntities, frameTick, projected, observerBudget);
            } finally {
                try {
                    localEntityOcclusion.flushFrame(observer);
                } finally {
                    try {
                        claimArbiter.flushFrame(observer);
                    } finally {
                        int blockEntityBudget = FidelitySettings.blockEntityBudgetPerTick;
                        for (PortalProjector projector : projected) {
                            blockEntityBudget -= projector.flushBlockEntities(blockEntityBudget);
                        }
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
                                    List<PortalProjector> projected,
                                    ProjectionBudgetLedger.ObserverFrame observerBudget) {
        UUID observerId = observer.getUniqueId();
        World observerWorld = observer.getWorld();
        Location observerLocation = observer.getLocation();
        Location eye = observer.getEyeLocation();
        claimArbiter.retryPending(observer, observerWorld);
        List<ILocalPortal> candidates = new ArrayList<ILocalPortal>();
        for (ILocalPortal portal : active.candidates(observerWorld, observerLocation)) {
            Location center = portal.getCenter();
            if (center == null || center.getWorld() == null) {
                continue;
            }
            if (!observerWorld.equals(center.getWorld())) {
                continue;
            }
            AxisAlignedBB view = portal.getView();
            if (view == null || !view.containsPrimitive(observerLocation.getX(), observerLocation.getY(), observerLocation.getZ())) {
                continue;
            }
            candidates.add(portal);
        }
        candidates.sort(Comparator.comparingDouble(portal -> distanceSquared(eye, portal)));

        List<ILocalPortal> interested = new ArrayList<ILocalPortal>(candidates.size());
        Map<UUID, PortalProjector.RtpProjectionTarget> rtpTargets = null;
        ProjectionManager.RtpProjectionProvider provider = rtpProjectionProvider.get();
        for (ILocalPortal portal : candidates) {
            if (ProjectionPortalOcclusion.isFullyOccluded(eye, interested, portal)) {
                continue;
            }
            Location center = portal.getCenter();
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
        Set<UUID> interestedIds = new HashSet<UUID>(interested.size());
        for (ILocalPortal portal : interested) {
            interestedIds.add(portal.getId());
        }
        interestSet.closeUnplanned(observerId, interestedIds, frameTick, FidelitySettings.dissolveTicks);
        interestSet.setRtpTargets(observerId, resolvedRtpTargets);
        List<PortalProjector> retiring = interestSet.retiringProjectors(observerId);
        List<ProjectionGazeScheduler.Candidate<ILocalPortal>> blockCandidates =
            new ArrayList<ProjectionGazeScheduler.Candidate<ILocalPortal>>(interested.size() + retiring.size());
        for (ILocalPortal portal : interested) {
            boolean pendingScan = interestSet.hasPendingScan(portal.getId(), observerId);
            if (updateBlocks || pendingScan) {
                blockCandidates.add(gazeCandidate(portal, eye, pendingScan, false));
            }
        }
        for (PortalProjector projector : retiring) {
            boolean pendingScan = projector.hasPendingScan();
            if (updateBlocks || pendingScan) {
                blockCandidates.add(gazeCandidate(projector.getPortal(), eye, pendingScan, true));
            }
        }
        int desiredBlocks = observerBudget.admitsBlocks()
            ? Math.min(Settings.PROJECTION_MAX_PORTALS_PER_OBSERVER_TICK, blockCandidates.size())
            : 0;
        int reservedUsed = Math.min(reservedBudget, desiredBlocks);
        int claimedBlocks = reservedUsed + ProjectionBudgetLedger.claim(remainingProjectors, desiredBlocks - reservedUsed);
        if (reservedBudget > reservedUsed) {
            remainingProjectors.addAndGet(reservedBudget - reservedUsed);
        }
        List<ILocalPortal> scheduledBlocks = interestSet.scheduleBlocks(observerId, gazeEye(eye), blockCandidates,
            claimedBlocks, frameTick);
        if (scheduledBlocks.size() < claimedBlocks) {
            remainingProjectors.addAndGet(claimedBlocks - scheduledBlocks.size());
        }
        Set<UUID> blockPortalIds = new HashSet<UUID>(scheduledBlocks.size());
        for (ILocalPortal portal : scheduledBlocks) {
            blockPortalIds.add(portal.getId());
        }
        ledger.recordScheduled(scheduledBlocks.size());
        ledger.recordDeferred(Math.max(0, blockCandidates.size() - scheduledBlocks.size()));
        BlockSlices slices = new BlockSlices(scheduledBlocks.size());
        projectRetiring(observer, retiring, blockPortalIds, projected, observerBudget, slices);
        projectActiveObserver(observer, priorityOrder(interested, interestedIds, scheduledBlocks, blockPortalIds),
            resolvedRtpTargets, blockPortalIds, updateEntities, projected, observerBudget, slices);
    }

    private static List<ILocalPortal> priorityOrder(List<ILocalPortal> interested, Set<UUID> interestedIds,
                                                    List<ILocalPortal> scheduledBlocks, Set<UUID> blockPortalIds) {
        if (scheduledBlocks.isEmpty()) {
            return interested;
        }
        List<ILocalPortal> ordered = new ArrayList<ILocalPortal>(interested.size());
        for (ILocalPortal portal : scheduledBlocks) {
            if (interestedIds.contains(portal.getId())) {
                ordered.add(portal);
            }
        }
        for (ILocalPortal portal : interested) {
            if (!blockPortalIds.contains(portal.getId())) {
                ordered.add(portal);
            }
        }
        return ordered;
    }

    private static ProjectionGazeScheduler.Eye gazeEye(Location eye) {
        return new ProjectionGazeScheduler.Eye(eye.getX(), eye.getY(), eye.getZ(), eye.getYaw(), eye.getPitch());
    }

    private static ProjectionGazeScheduler.Candidate<ILocalPortal> gazeCandidate(ILocalPortal portal, Location eye,
                                                                               boolean pendingScan, boolean retiring) {
        AxisAlignedBB area = portal.getArea();
        if (area != null) {
            return new ProjectionGazeScheduler.Candidate<ILocalPortal>(portal, portal.getId(),
                area.getXa(), area.getYa(), area.getZa(), area.getXb(), area.getYb(), area.getZb(),
                pendingScan, retiring);
        }
        Location center = portal.getCenter();
        double x = center == null ? eye.getX() : center.getX();
        double y = center == null ? eye.getY() : center.getY();
        double z = center == null ? eye.getZ() : center.getZ();
        return new ProjectionGazeScheduler.Candidate<ILocalPortal>(portal, portal.getId(),
            x - 0.5D, y - 0.5D, z - 0.5D, x + 0.5D, y + 0.5D, z + 0.5D, pendingScan, retiring);
    }

    private void projectRetiring(Player observer, List<PortalProjector> retiring, Set<UUID> blockPortalIds,
                                 List<PortalProjector> projected,
                                 ProjectionBudgetLedger.ObserverFrame observerBudget, BlockSlices slices) {
        for (PortalProjector projector : retiring) {
            if (!blockPortalIds.contains(projector.getPortal().getId())) {
                continue;
            }
            long deadlineNanos = slices.next(observerBudget.deadlineNanos());
            try {
                observerBudget.recordBlockWork();
                projector.project(true, false, deadlineNanos);
                if (!projector.isClosed()) {
                    projected.add(projector);
                }
            } catch (Throwable ex) {
                Wormholes.instance.getLogger().log(Level.WARNING,
                    "[ProjectionManager] dissolve error portal=" + projector.getPortal().getName() + " observer=" + observer.getName(), ex);
            }
        }
    }

    private void projectActiveObserver(Player observer, List<ILocalPortal> scheduledPortals,
                                       Map<UUID, PortalProjector.RtpProjectionTarget> rtpTargets,
                                       Set<UUID> blockPortalIds, boolean updateEntities,
                                       List<PortalProjector> projected,
                                       ProjectionBudgetLedger.ObserverFrame observerBudget, BlockSlices slices) {
        if (!alive.getAsBoolean() || observer == null || !observer.isOnline()) {
            return;
        }
        for (ILocalPortal portal : scheduledPortals) {
            boolean updateBlocks = blockPortalIds.contains(portal.getId());
            if (!updateBlocks && !updateEntities) {
                continue;
            }
            long deadlineNanos = updateBlocks ? slices.next(observerBudget.deadlineNanos()) : observerBudget.deadlineNanos();
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
                if (updateBlocks) {
                    observerBudget.recordBlockWork();
                }
                projector.project(updateBlocks, updateEntities, deadlineNanos);
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

    private static final class BlockSlices {
        private int remaining;

        private BlockSlices(int scheduled) {
            this.remaining = scheduled;
        }

        private long next(long frameDeadlineNanos) {
            int share = Math.max(1, remaining);
            remaining = Math.max(0, remaining - 1);
            if (share == 1 || frameDeadlineNanos == Long.MAX_VALUE) {
                return frameDeadlineNanos;
            }
            long now = System.nanoTime();
            if (now >= frameDeadlineNanos) {
                return frameDeadlineNanos;
            }
            return now + (frameDeadlineNanos - now) / share;
        }
    }
}
