package art.arcane.wormholes.render;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation.EntityAnimationType;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.service.WormholesTelemetry;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.portal.rtp.RtpProjectionView;
import art.arcane.wormholes.render.view.ProjectionEntityView;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.render.view.ProjectionWorldViewProvider;
import art.arcane.wormholes.render.view.RemoteWorldView;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

public final class PortalProjector {
    static final double REUSE_EYE_EPSILON_SQUARED = 0.0625D;

    private final ILocalPortal portal;
    private final Player observer;
    private final UUID observerId;
    private final UUID localWorldId;
    private final ProjectionClaimArbiter claimArbiter;
    private final ProjectionWorldViewProvider viewProvider;
    private final BooleanSupplier activeGuard;
    private final ProjectorDestination destination;
    private final ProjectorSampleMemo sampleMemo;
    private final ProjectorSampler sampler;
    private final ProjectorBlackoutSeal blackout;
    private final ProjectorViewFrustum viewFrustum;
    private final ProjectorResampleSchedule schedule;
    private final ProjectorCellScan cellScan;
    private final ProjectorFrustumFailures frustumFailures;
    private final ProjectedEntityRenderer entityRenderer = new ProjectedEntityRenderer();
    private final ProjectorBlackoutDisplayRenderer blackoutDisplayRenderer =
        new ProjectorBlackoutDisplayRenderer();

    private volatile World claimWorld;
    private volatile UUID claimWorldId;
    private boolean firstProjectionDone;
    private volatile boolean closed;
    private volatile boolean discardRequested;
    private long lastDiagLogCall;
    private long lastProjectNanos;
    private int lastBlockChanges;
    private volatile int lastRenderedCells;
    private int lastReuseSkips;
    private int lastClaimConflicts;
    private int lastWinnerChanges;
    private int lastClaimReverts;
    private int initialFullSendPassesRemaining;
    private double lastEyeX;
    private double lastEyeY;
    private double lastEyeZ;
    private boolean hasCameraSnapshot;
    private volatile boolean reuseInvalidated;
    private RtpProjectionTarget rtpProjectionTarget;
    private ProjectionRenderMode lastRenderMode;

    public PortalProjector(ILocalPortal portal, Player observer, ProjectionClaimArbiter claimArbiter,
                           ProjectionWorldViewProvider viewProvider, BooleanSupplier activeGuard) {
        this.portal = portal;
        this.observer = observer;
        this.observerId = observer.getUniqueId();
        World constructionWorld = portal.getWorld();
        this.localWorldId = constructionWorld == null ? null : constructionWorld.getUID();
        this.claimArbiter = claimArbiter;
        this.viewProvider = viewProvider;
        this.activeGuard = activeGuard;
        this.destination = new ProjectorDestination(portal, viewProvider);
        this.sampleMemo = new ProjectorSampleMemo();
        this.sampler = new ProjectorSampler(sampleMemo, new ProjectorRecursivePortals(), destination::liveView);
        this.blackout = new ProjectorBlackoutSeal();
        this.viewFrustum = new ProjectorViewFrustum();
        this.schedule = new ProjectorResampleSchedule(portal);
        this.cellScan = new ProjectorCellScan(portal, sampler, sampleMemo, blackout);
        this.frustumFailures = new ProjectorFrustumFailures();
        this.claimWorld = constructionWorld;
        this.claimWorldId = this.localWorldId;
        this.firstProjectionDone = false;
        this.closed = false;
        this.discardRequested = false;
        this.lastDiagLogCall = 0L;
        this.lastProjectNanos = 0L;
        this.lastBlockChanges = 0;
        this.lastRenderedCells = 0;
        this.lastReuseSkips = 0;
        this.lastClaimConflicts = 0;
        this.lastWinnerChanges = 0;
        this.lastClaimReverts = 0;
        this.initialFullSendPassesRemaining = Math.max(0, Settings.PROJECTION_INITIAL_RESEND_PASSES);
        this.lastEyeX = 0.0D;
        this.lastEyeY = 0.0D;
        this.lastEyeZ = 0.0D;
        this.hasCameraSnapshot = false;
        this.reuseInvalidated = false;
        this.lastRenderMode = null;
    }

    public ILocalPortal getPortal() {
        return portal;
    }

    public Player getObserver() {
        return observer;
    }

    public void setRtpProjectionTarget(RtpProjectionTarget target) {
        RtpProjectionTarget previous = rtpProjectionTarget;
        rtpProjectionTarget = target;
        if (target == null) {
            if (previous != null) {
                invalidateRtpDestinationState();
            }
            return;
        }
        if (target.requiresDestinationInvalidation(previous)) {
            invalidateRtpDestinationState();
        }
    }

    public boolean isClosed() {
        return closed;
    }

    public void invalidateProjectionReuse() {
        reuseInvalidated = true;
    }

    public int getProjectedCount() {
        return lastRenderedCells;
    }

    public int getSpoofedEntityCount() {
        return entityRenderer.getSpoofedCount() + blackoutDisplayRenderer.getPaneCount();
    }

    public boolean hasProjectedEntity(UUID entityId) {
        return entityRenderer.hasProjectedEntity(entityId);
    }

    public void sendProjectedEntityAnimation(UUID entityId, EntityAnimationType type) {
        if (closed) {
            return;
        }
        entityRenderer.sendAnimation(observer, entityId, type);
    }

    public void sendProjectedEntityHurt(UUID entityId, float yaw) {
        if (closed) {
            return;
        }
        entityRenderer.sendHurt(observer, entityId, yaw);
    }

    public String getDiagnostics() {
        return "mode=" + portal.getRenderMode().displayName()
            + " fittedDepth=" + viewFrustum.fittedDepth()
            + " fittedLateral=" + viewFrustum.fittedLateral()
            + " candidateWork=" + viewFrustum.fittedCandidateWork()
            + " rendered=" + lastRenderedCells
            + " changes=" + lastBlockChanges
            + " planeReject=" + cellScan.planeRejected()
            + " windowReject=" + cellScan.windowRejected()
            + " frustumReject=" + cellScan.frustumRejected()
            + " occlusionReject=" + cellScan.occlusionRejected()
            + " occlusionSteps=" + cellScan.occlusionVoxelSteps()
            + " occlusionBudgetExhausted=" + cellScan.occlusionBudgetExhausted()
            + " remoteSamples=" + sampler.remoteSampleCount()
            + " reuseSkips=" + lastReuseSkips
            + " maskAir=" + cellScan.maskedCells()
            + " blackoutPanes=" + blackoutDisplayRenderer.getPaneCount()
            + " blackoutFallback=" + cellScan.blackoutMesh().fallback()
            + " blackoutSpawns=" + blackoutDisplayRenderer.getSpawns()
            + " blackoutMetadata=" + blackoutDisplayRenderer.getMetadataUpdates()
            + " blackoutDestroys=" + blackoutDisplayRenderer.getDestroys()
            + " claimConflicts=" + lastClaimConflicts
            + " winnerChanges=" + lastWinnerChanges
            + " claimReverts=" + lastClaimReverts
            + " frustumFailures=" + frustumFailures.total()
            + " nanos=" + lastProjectNanos;
    }

    public void project() {
        project(true, true);
    }

    public void project(boolean updateBlocks, boolean updateEntities) {
        if (!activeGuard.getAsBoolean()) {
            close();
            return;
        }
        if (discardRequested) {
            discard();
            return;
        }
        if (closed) {
            return;
        }
        if (updateEntities) {
            updateEntities = schedule.entityUpdateDue();
        }
        if (!updateBlocks && !updateEntities) {
            return;
        }

        long startNanos = System.nanoTime();

        if (!portal.isOpen()) {
            Wormholes.v("[Projector] portal " + portal.getName() + " no longer open, closing projector");
            close();
            return;
        }

        RtpProjectionTarget rtpTarget = rtpProjectionTarget;
        ProjectorDestination.Outcome outcome = destination.resolve(observer, rtpTarget);
        if (outcome == ProjectorDestination.Outcome.CLOSE) {
            close();
            return;
        }
        if (outcome == ProjectorDestination.Outcome.WAIT) {
            return;
        }

        Location eye = observer.getEyeLocation();
        if (!updateBlocks) {
            updateEntitiesOnly(startNanos, eye);
            return;
        }

        schedule.beginBlockPass();
        if (destination.remoteView instanceof RemoteWorldView remoteResendView) {
            maybeForceRemoteResend(remoteResendView);
        }
        World destWorld = destination.destWorld;
        double destinationOriginX = destination.originX;
        double destinationOriginZ = destination.originZ;
        ProjectionRenderMode renderMode = portal.getRenderMode();
        boolean renderModeChanged = renderMode != lastRenderMode;
        boolean viewCameraMoved = requiresViewCellResample(renderMode, hasCameraSnapshot,
            eye.getX(), eye.getY(), eye.getZ(), lastEyeX, lastEyeY, lastEyeZ);
        boolean stableResample = schedule.stableResample(firstProjectionDone, destination.destView,
            destWorld, destinationOriginX, destinationOriginZ);
        boolean localDirty = sampleMemo.localRegionDirty(localWorldId);
        if (!renderModeChanged && canReuseProjection(eye, stableResample, localDirty)) {
            lastReuseSkips++;
            lastBlockChanges = 0;
            lastProjectNanos = System.nanoTime() - startNanos;
            WormholesTelemetry.addRenderNanos(lastProjectNanos);
            if (updateEntities) {
                updateEntitiesOnly(startNanos, eye);
            }
            return;
        }

        double portalDepth = portal.getNetworkViewDepth();
        Frustum4D next;
        try {
            next = viewFrustum.fit(observer, portal.getStructure(), portal.getFrame(), eye, portalDepth,
                portal.getNetworkViewLateralPad());
        } catch (RuntimeException ex) {
            noteFrustumFailure("block", ex);
            return;
        }
        frustumFailures.recordSuccess();
        double depthBlocks = viewFrustum.fittedDepth();
        if (portal.isBlackoutBackground()) {
            blackout.beginPass(portal.getBlackoutColor());
        } else {
            blackout.disable();
        }
        boolean buriedCellCulling = renderMode.usesBuriedCellCulling();
        if (sampler.setBuriedCellCullingPass(buriedCellCulling)) {
            sampleMemo.clearDestinationSamples();
        }

        if (!firstProjectionDone) {
            Wormholes.v("[Projector] portal=" + portal.getName() + " observer=" + observer.getName()
                + " first frustum: faceCount=" + next.getFaceCount() + " region=" + formatBox(next.getRegion())
                + " depth=" + depthBlocks + " requestedDepth=" + portalDepth
                + " fittedLateral=" + viewFrustum.fittedLateral()
                + " candidateWork=" + viewFrustum.fittedCandidateWork()
                + " lateralPad=" + portal.getNetworkViewLateralPad()
                + " aperturePadding=" + Settings.PROJECTION_APERTURE_PADDING_BLOCKS);
        }

        boolean forceStableCellResample = schedule.consumeForcedResample(stableResample);
        if (renderModeChanged || viewCameraMoved) {
            forceStableCellResample = true;
        }

        long destinationRevision = destination.destView.getRevision();
        boolean destinationSamplesStale = forceStableCellResample
            || sampler.recursiveSamplesCached()
            || sampleMemo.destinationStale(destinationRevision, destWorld != null,
                sinceVersion -> schedule.destinationDirty(destWorld, destinationOriginX, destinationOriginZ, sinceVersion))
            || sampleMemo.destinationOverBudget(sampleMemoBudget());
        if (destinationSamplesStale) {
            sampler.clearRecursivePortals();
            sampleMemo.clearDestinationSamples();
            sampleMemo.refreshDestination(destinationRevision);
        }
        sampler.resetRecursiveSamplesCached();
        sampleMemo.refreshLocal(forceStableCellResample, localDirty, destination.localView.getRevision(), sampleMemoBudget());
        sampleMemo.expandLocalRegionRect(next.getRegion());
        sampleMemo.markLocalScanned();

        boolean forceFullSend = initialFullSendPassesRemaining > 0;
        sampler.resetRemoteSampleCount();
        lastBlockChanges = 0;
        lastClaimConflicts = 0;
        lastWinnerChanges = 0;
        lastClaimReverts = 0;
        cellScan.run(destination, rtpTarget, eye, next, depthBlocks, forceStableCellResample, forceFullSend,
            buriedCellCulling, renderMode);

        if (!activeGuard.getAsBoolean()) {
            close();
            return;
        }
        if (discardRequested) {
            discard();
            return;
        }

        ProjectorBlackoutMesh.Result blackoutMesh = cellScan.blackoutMesh();
        boolean displayReady = blackoutMesh.fallback()
            ? blackoutDisplayRenderer.prepareEmpty()
            : blackoutDisplayRenderer.prepare(observer, blackoutMesh.panels(), cellScan.blackoutData(),
                depthBlocks);
        if (!displayReady) {
            cellScan.dropBlackoutDisplay();
            blackoutDisplayRenderer.prepareEmpty();
        }

        World submitWorld = destination.localWorld;
        noteClaimWorld(submitWorld);
        ProjectionClaimArbiter.ClaimUpdateResult claimResult = claimArbiter.submit(observer, portal, submitWorld,
            cellScan.claims(), Math.abs(cellScan.eyeDot()), Settings.LIGHTING_FIDELITY && schedule.lightingUpdatePass(firstProjectionDone));
        lastBlockChanges = claimResult.getBlockChanges();
        lastClaimConflicts = claimResult.getConflicts();
        lastWinnerChanges = claimResult.getWinnerChanges();
        lastClaimReverts = claimResult.getReverts();
        lastRenderedCells = cellScan.claims().size();

        if (forceFullSend && initialFullSendPassesRemaining > 0) {
            initialFullSendPassesRemaining--;
        }

        if (updateEntities) {
            updateProjectedEntities(next, depthBlocks, !cellScan.claims().isEmpty(),
                cellScan.localFrame(), cellScan.remoteFrame());
        }
        schedule.noteSourceViewRevision(destinationRevision);
        lastProjectNanos = System.nanoTime() - startNanos;
        WormholesTelemetry.addRenderNanos(lastProjectNanos);

        long passCount = schedule.passCount();
        boolean shouldLog = passCount <= 3L || (passCount - lastDiagLogCall) >= 50L;
        if (shouldLog && (cellScan.enterCount() > 0 || cellScan.exitCount() > 0)) {
            Wormholes.v("[Projector] portal=" + portal.getName() + " observer=" + observer.getName()
                + " diff: enter=" + cellScan.enterCount() + " exit=" + cellScan.exitCount() + " kept=" + cellScan.keptCount()
                + " " + getDiagnostics() + " call#" + passCount);
            lastDiagLogCall = passCount;
        }

        cellScan.commit();

        firstProjectionDone = true;
        lastRenderMode = renderMode;
        rememberCamera(eye);
    }

    public void finishBlackoutDisplayFrame() {
        if (!closed) {
            blackoutDisplayRenderer.finish(observer);
        }
    }

    private void updateEntitiesOnly(long startNanos, Location eye) {
        if (destination.destAnchor == null || !firstProjectionDone || !cellScan.hasProjection()) {
            lastProjectNanos = System.nanoTime() - startNanos;
            WormholesTelemetry.addRenderNanos(lastProjectNanos);
            return;
        }

        double portalDepth = portal.getNetworkViewDepth();
        Frustum4D frustum;
        try {
            frustum = viewFrustum.fit(observer, portal.getStructure(), portal.getFrame(), eye, portalDepth,
                portal.getNetworkViewLateralPad());
        } catch (RuntimeException ex) {
            noteFrustumFailure("entity", ex);
            return;
        }
        frustumFailures.recordSuccess();
        double depthBlocks = viewFrustum.fittedDepth();

        PortalFrame localFrame = portal.getFrame();
        PortalFrame remoteFrame = destination.mirrorMode ? localFrame.flipNormal() : destination.destAnchor.getFrame();
        double localOriginX = portal.getOrigin().getX();
        double localOriginY = portal.getOrigin().getY();
        double localOriginZ = portal.getOrigin().getZ();
        double facingX = localFrame.getNormal().x();
        double facingY = localFrame.getNormal().y();
        double facingZ = localFrame.getNormal().z();
        double eyeRelX = eye.getX() - localOriginX;
        double eyeRelY = eye.getY() - localOriginY;
        double eyeRelZ = eye.getZ() - localOriginZ;
        boolean eyeFrontSide = (eyeRelX * facingX + eyeRelY * facingY + eyeRelZ * facingZ) >= 0.0D;
        PortalFrame projectionLocalFrame = viewFrame(localFrame, eyeFrontSide);
        PortalFrame projectionRemoteFrame = viewFrame(remoteFrame, eyeFrontSide);
        updateProjectedEntities(frustum, depthBlocks, true, projectionLocalFrame, projectionRemoteFrame);
        lastProjectNanos = System.nanoTime() - startNanos;
        WormholesTelemetry.addRenderNanos(lastProjectNanos);
    }

    private void updateProjectedEntities(Frustum4D frustum,
                                         double depthBlocks,
                                         boolean hasVisibleProjection,
                                         PortalFrame projectionLocalFrame,
                                         PortalFrame projectionRemoteFrame) {
        IPortal destAnchor = destination.destAnchor;
        if (!hasVisibleProjection || destAnchor == null) {
            entityRenderer.close(observer);
            return;
        }
        ProjectionWorldView destView = destination.destView;
        boolean mirrorMode = destination.mirrorMode;
        int mirrorRotationQuarterTurns = destination.mirrorRotationQuarterTurns;
        if (viewProvider.usesRegionSnapshots() && destView instanceof ProjectionEntityView entityView) {
            entityRenderer.applySnapshot(observer, portal, destAnchor, mirrorMode, mirrorRotationQuarterTurns,
                entityView, frustum, depthBlocks,
                projectionLocalFrame, projectionRemoteFrame);
            return;
        }
        if (destination.dest != null) {
            entityRenderer.apply(observer, portal, destination.dest, frustum, depthBlocks, projectionLocalFrame,
                projectionRemoteFrame, mirrorRotationQuarterTurns);
            return;
        }
        if (destView instanceof RemoteWorldView remoteWorldView) {
            double remoteOriginX = destAnchor.getOrigin().getX();
            double remoteOriginY = destAnchor.getOrigin().getY();
            double remoteOriginZ = destAnchor.getOrigin().getZ();
            entityRenderer.applyRemote(observer, portal, remoteOriginX, remoteOriginY, remoteOriginZ,
                remoteWorldView, frustum, depthBlocks, projectionLocalFrame, projectionRemoteFrame);
        }
    }

    private void maybeForceRemoteResend(RemoteWorldView remoteView) {
        // A remote block changed: re-evaluate every projected cell next frame so cells that became
        // air drop out of nextProjected and the claim arbiter REVERTS them (restores the real local
        // block). Do NOT releaseSilently/clear here -- that forgets the already-sent fake blocks
        // without reverting, leaving a stale block (e.g. a broken block stuck in the projection).
        schedule.noteRemoteRevision(remoteView.getRevision());
        if (!schedule.fullRemoteResendDue()) {
            return;
        }
        cellScan.clear();
        hasCameraSnapshot = false;
        initialFullSendPassesRemaining = Math.max(initialFullSendPassesRemaining, Math.max(1, Settings.PROJECTION_INITIAL_RESEND_PASSES));
    }

    private int sampleMemoBudget() {
        return ProjectorSampleMemo.budgetFor(lastRenderedCells);
    }

    void noteClaimWorld(World world) {
        if (world == claimWorld) {
            return;
        }
        claimWorld = world;
        claimWorldId = world == null ? null : world.getUID();
    }

    private void noteFrustumFailure(String stage, RuntimeException ex) {
        int consecutive = frustumFailures.recordFailure();
        boolean exhausted = ProjectorFrustumFailures.exhausted(consecutive);
        Wormholes plugin = Wormholes.instance;
        if (plugin != null) {
            plugin.getLogger().log(Level.WARNING, "[Projector] failed to build " + stage + " frustum for portal "
                + portal.getName() + " observer " + observer.getName()
                + " (consecutive=" + consecutive + " total=" + frustumFailures.total()
                + (exhausted ? ", closing projector)" : ")"), ex);
        }
        if (exhausted) {
            close();
        }
    }

    static boolean shouldMaskRecursivePortalAperture(boolean traversable, boolean cycle, int remainingDepth) {
        return !traversable || cycle || remainingDepth <= 0;
    }

    static boolean shouldProjectAirSample(ProjectorSample.Kind kind, boolean localAir) {
        return kind == ProjectorSample.Kind.MASK_AIR || (kind == ProjectorSample.Kind.REMOTE_AIR && !localAir);
    }

    static boolean projectsBehindPortalPlane(double signedCellDistance, boolean eyeFrontSide, double portalPlaneClearance) {
        if (Math.abs(signedCellDistance) <= portalPlaneClearance) {
            return false;
        }
        boolean cellFrontSide = signedCellDistance >= 0.0D;
        return cellFrontSide != eyeFrontSide;
    }

    static double portalPlaneClearance(AxisAlignedBB area, PortalFrame frame) {
        double normalDepth;
        if (frame.getNormal().x() != 0) {
            normalDepth = area.sizeX();
        } else if (frame.getNormal().y() != 0) {
            normalDepth = area.sizeY();
        } else {
            normalDepth = area.sizeZ();
        }
        return Math.max(0.5001D, (normalDepth * 0.5D) + 0.001D);
    }

    static PortalFrame viewFrame(PortalFrame frame, boolean frontSide) {
        return frame.view(frontSide);
    }

    static int minBlockForCenter(double centerMin) {
        return (int) Math.ceil(centerMin - 0.500001D);
    }

    static int maxBlockForCenter(double centerMax) {
        return (int) Math.floor(centerMax - 0.499999D);
    }

    private boolean canReuseProjection(Location eye, boolean stableResample, boolean localDirty) {
        if (reuseInvalidated) {
            return false;
        }
        boolean lightingBlocked = claimArbiter.hasPendingLighting(observer) && schedule.lightingUpdatePass(firstProjectionDone);
        Direction normal = portal.getFrame().getNormal();
        double originNormal = axisValueOf(portal.getOrigin().getX(), portal.getOrigin().getY(), portal.getOrigin().getZ(), normal);
        boolean sideFlipped = hasCameraSnapshot
            && (axisValueOf(eye.getX(), eye.getY(), eye.getZ(), normal) >= originNormal)
                != (axisValueOf(lastEyeX, lastEyeY, lastEyeZ, normal) >= originNormal);
        return canReuseProjection(firstProjectionDone, cellScan.hasProjection(), hasCameraSnapshot,
            initialFullSendPassesRemaining, stableResample, schedule.isRemoteResamplePending(), lightingBlocked, sideFlipped,
            localDirty,
            eye.getX(), eye.getY(), eye.getZ(), lastEyeX, lastEyeY, lastEyeZ);
    }

    private static double axisValueOf(double x, double y, double z, Direction normal) {
        if (normal.x() != 0) {
            return x;
        }
        if (normal.y() != 0) {
            return y;
        }
        return z;
    }

    static boolean canReuseProjection(boolean firstProjectionDone,
                                      boolean hasProjection,
                                      boolean hasCameraSnapshot,
                                      int initialFullSendPassesRemaining,
                                      boolean stableResample,
                                      boolean pendingRemoteResample,
                                      boolean lightingBlocked,
                                      boolean sideFlipped,
                                      boolean localDirty,
                                      double eyeX,
                                      double eyeY,
                                      double eyeZ,
                                      double lastEyeX,
                                      double lastEyeY,
                                      double lastEyeZ) {
        if (!firstProjectionDone || !hasProjection || !hasCameraSnapshot) {
            return false;
        }
        if (initialFullSendPassesRemaining > 0 || stableResample || pendingRemoteResample || lightingBlocked) {
            return false;
        }
        if (sideFlipped || localDirty) {
            return false;
        }
        double dx = eyeX - lastEyeX;
        double dy = eyeY - lastEyeY;
        double dz = eyeZ - lastEyeZ;
        double movedSquared = (dx * dx) + (dy * dy) + (dz * dz);
        return movedSquared < REUSE_EYE_EPSILON_SQUARED;
    }

    static boolean requiresViewCellResample(ProjectionRenderMode renderMode,
                                               boolean hasCameraSnapshot,
                                               double eyeX,
                                               double eyeY,
                                               double eyeZ,
                                               double lastEyeX,
                                               double lastEyeY,
                                               double lastEyeZ) {
        if (renderMode == null || !renderMode.usesObserverOcclusion() || !hasCameraSnapshot) {
            return false;
        }
        double dx = eyeX - lastEyeX;
        double dy = eyeY - lastEyeY;
        double dz = eyeZ - lastEyeZ;
        return (dx * dx) + (dy * dy) + (dz * dz) >= REUSE_EYE_EPSILON_SQUARED;
    }

    private void rememberCamera(Location eye) {
        lastEyeX = eye.getX();
        lastEyeY = eye.getY();
        lastEyeZ = eye.getZ();
        hasCameraSnapshot = true;
        reuseInvalidated = false;
    }

    private void invalidateRtpDestinationState() {
        schedule.invalidateDestination();
        sampler.resetRecursiveSamplesCached();
        sampleMemo.discard();
        sampler.clearRecursivePortals();
        entityRenderer.close(observer);
        blackoutDisplayRenderer.close(observer);
    }

    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;

        if (releaseClaims()) {
            blackoutDisplayRenderer.close(observer);
            entityRenderer.close(observer);
        } else {
            blackoutDisplayRenderer.discard();
            entityRenderer.discard(observer);
        }
        cellScan.clear();
        lastRenderedCells = 0;
    }

    boolean releaseClaims() {
        World releaseWorld = claimWorld;
        UUID releaseWorldId = claimWorldId;
        if (observer == null || !observer.isOnline()) {
            claimArbiter.discardObserver(observerId, releaseWorldId);
            return false;
        }

        World observerWorld = observer.getWorld();
        if (releaseWorld == null || releaseWorldId == null || observerWorld == null
            || !releaseWorldId.equals(observerWorld.getUID())) {
            claimArbiter.discardObserver(observerId, releaseWorldId);
            return false;
        }

        ProjectionClaimArbiter.ClaimUpdateResult result = claimArbiter.release(observer, portal, releaseWorld, true);
        if (result.getBlockChanges() > 0) {
            Wormholes.v("[Projector] portal=" + portal.getName() + " observer=" + observer.getName()
                + " close: reverted=" + result.getBlockChanges());
        }
        return true;
    }

    public synchronized void discard() {
        if (closed) {
            return;
        }
        closed = true;
        if (observer != null) {
            claimArbiter.discardObserver(observerId, claimWorldId);
        }
        cellScan.clear();
        lastRenderedCells = 0;
        blackoutDisplayRenderer.discard();
        entityRenderer.discard(observer);
    }

    public void requestDiscard() {
        discardRequested = true;
    }

    private static String formatBox(AxisAlignedBB box) {
        if (box == null) {
            return "null";
        }
        return "[" + box.getXa() + "," + box.getYa() + "," + box.getZa()
            + " -> " + box.getXb() + "," + box.getYb() + "," + box.getZb() + "]";
    }

    public record RtpProjectionTarget(World world, double originX, double originY, double originZ,
                                      PortalFrame frame, long routeRevision) {
        public RtpProjectionTarget {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(frame, "frame");
            if (!Double.isFinite(originX) || !Double.isFinite(originY) || !Double.isFinite(originZ)) {
                throw new IllegalArgumentException("RTP projection target coordinates must be finite");
            }
            if (routeRevision < 0L) {
                throw new IllegalArgumentException("routeRevision must be non-negative");
            }
        }

        public static RtpProjectionTarget from(RtpProjectionView.ReadyData readyData, World world) {
            RtpProjectionView.ReadyData requiredReadyData = Objects.requireNonNull(readyData, "readyData");
            RtpProjectionView.Target target = requiredReadyData.target();
            Direction normal = direction(target.forward(), "forward").reverse();
            Direction right = direction(target.right(), "right");
            Direction up = direction(target.up(), "up");
            PortalFrame frame = new PortalFrame(normal, right, up);
            RtpProjectionView.Point3 safeFeet = target.safeFeet();
            return new RtpProjectionTarget(world, safeFeet.x(), safeFeet.y(), safeFeet.z(), frame,
                    requiredReadyData.routeRevision());
        }

        public boolean requiresDestinationInvalidation(RtpProjectionTarget previous) {
            return previous == null || routeRevision != previous.routeRevision;
        }

        private static Direction direction(RtpProjectionView.Vector3 vector, String name) {
            RtpProjectionView.Vector3 requiredVector = Objects.requireNonNull(vector, name);
            double lengthSquared = requiredVector.x() * requiredVector.x()
                    + requiredVector.y() * requiredVector.y()
                    + requiredVector.z() * requiredVector.z();
            if (lengthSquared <= 1.0E-12D) {
                throw new IllegalArgumentException(name + " must not be zero");
            }
            return Direction.closest(requiredVector.x(), requiredVector.y(), requiredVector.z());
        }
    }
}
