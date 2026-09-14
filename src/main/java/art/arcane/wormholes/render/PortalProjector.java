package art.arcane.wormholes.render;

import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation.EntityAnimationType;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.service.WormholesTelemetry;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.RemotePortal;
import art.arcane.wormholes.portal.UniversalTunnel;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.portal.rtp.RtpProjectionView;
import art.arcane.wormholes.render.atmosphere.AtmosphereChannel;
import art.arcane.wormholes.render.atmosphere.AtmosphereMode;
import art.arcane.wormholes.render.atmosphere.FogPlatePolicy;
import art.arcane.wormholes.render.atmosphere.WeatherRelay;
import art.arcane.wormholes.render.acoustics.AcousticsBridge;
import art.arcane.wormholes.render.acoustics.AcousticsProfile;
import art.arcane.wormholes.render.bedrock.ClientProfileService;
import art.arcane.wormholes.render.blockentity.BlockEntityPacketSink;
import art.arcane.wormholes.render.blockentity.ProjectedBlockEntityLayer;
import art.arcane.wormholes.render.lod.DissolveSchedule;
import art.arcane.wormholes.render.lod.LodPolicy;
import art.arcane.wormholes.render.plate.ViewPlate;
import art.arcane.wormholes.render.plate.ViewPlateBuilder;
import art.arcane.wormholes.render.plate.ViewPlateCache;
import art.arcane.wormholes.render.plate.ViewPlateKey;
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
    private final ProjectedEntityRenderer entityRenderer;
    private final ProjectorBlackoutDisplayRenderer blackoutDisplayRenderer =
        new ProjectorBlackoutDisplayRenderer();
    private final ViewPlateCache plateCache;
    private final AtmosphereChannel atmosphere = new AtmosphereChannel();
    private final WeatherRelay weather = new WeatherRelay();
    private final Random weatherRandom = new Random();
    private final ProjectedBlockEntityLayer blockEntityLayer = new ProjectedBlockEntityLayer(new BlockEntityPacketSink());
    private final DissolveSchedule dissolve = new DissolveSchedule();
    private long blockPasses;

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
    private int occlusionResumes;
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
    private long lastPresentationRevision;
    private boolean lastPassUsedPlate;
    private final AtomicLong invalidationGeneration = new AtomicLong();
    private long lastCommittedGeneration;
    private volatile PendingProjection pendingProjection;
    private long scanSlices;
    private long completedScans;
    private long lastScanNanos;
    private long lastFinalizeNanos;

    public PortalProjector(ILocalPortal portal, Player observer, ProjectionClaimArbiter claimArbiter,
                           ProjectionWorldViewProvider viewProvider, BooleanSupplier activeGuard) {
        this(portal, observer, claimArbiter, viewProvider, activeGuard, new EntityRenderLocalOcclusionArbiter(), null);
    }

    public PortalProjector(ILocalPortal portal,
                           Player observer,
                           ProjectionClaimArbiter claimArbiter,
                           ProjectionWorldViewProvider viewProvider,
                           BooleanSupplier activeGuard,
                           EntityRenderLocalOcclusionArbiter localEntityOcclusion,
                           ViewPlateCache plateCache) {
        this.plateCache = plateCache;
        this.portal = portal;
        this.observer = observer;
        this.observerId = observer.getUniqueId();
        World constructionWorld = portal.getWorld();
        this.localWorldId = constructionWorld == null ? null : constructionWorld.getUID();
        this.claimArbiter = claimArbiter;
        this.viewProvider = viewProvider;
        this.activeGuard = activeGuard;
        this.entityRenderer = new ProjectedEntityRenderer(localEntityOcclusion, portal.getId());
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

    public boolean hasProjection() {
        return firstProjectionDone && cellScan.hasProjection();
    }

    public boolean hasPendingScan() {
        return pendingProjection != null;
    }

    public boolean isRetiring() {
        return dissolve.isRetiring();
    }

    public void beginRetire(long frameTick, int dissolveTicks) {
        dissolve.beginRetire(blockPasses, dissolveTicks);
    }

    public void cancelRetire() {
        dissolve.cancelRetire();
    }

    public boolean retireComplete(long frameTick) {
        return dissolve.retireComplete(blockPasses);
    }

    public void invalidateProjectionReuse() {
        invalidationGeneration.incrementAndGet();
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

    public Set<UUID> getProjectedEntityIds() {
        return entityRenderer.getProjectedEntityIds();
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
            + " frustumMaskRows=" + cellScan.frustumMaskedRows()
            + " frustumScalarRows=" + cellScan.frustumScalarRows()
            + " occlusionReject=" + cellScan.occlusionRejected()
            + " occlusionSteps=" + cellScan.occlusionVoxelSteps()
            + " occlusionResumes=" + occlusionResumes
            + " occlusionProofHits=" + cellScan.occlusionProofHits()
            + " occlusionProofRevalidations=" + cellScan.occlusionProofRevalidations()
            + " occlusionProofInvalidations=" + cellScan.occlusionProofInvalidations()
            + " adjacentOcclusionHits=" + cellScan.adjacentOcclusionHits()
            + " unresolvedOcclusion=" + cellScan.unresolvedOcclusionCells()
            + " occlusionBudgetExhausted=" + cellScan.occlusionBudgetExhausted()
            + " remoteSamples=" + sampler.remoteSampleCount()
            + " emptyCellSkips=" + cellScan.emptyCellSkips()
            + " reuseSkips=" + lastReuseSkips
            + " scanSlices=" + scanSlices
            + " completedScans=" + completedScans
            + " scanPending=" + hasPendingScan()
            + " scanNanos=" + lastScanNanos
            + " finalizeNanos=" + lastFinalizeNanos
            + " maskAir=" + cellScan.maskedCells()
            + " plate=" + lastPassUsedPlate
            + " plateHits=" + cellScan.plateHits()
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
        project(updateBlocks, updateEntities, Long.MAX_VALUE);
    }

    public void project(boolean updateBlocks, boolean updateEntities, long deadlineNanos) {
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
        entityRenderer.setViewerProfile(ClientProfileService.profileFor(observer));
        PendingProjection pending = pendingProjection;
        if (pending != null && !matchesPendingContext(pending, eye, rtpTarget)) {
            cancelPendingProjection();
            pending = null;
        }
        if (!updateBlocks) {
            updateEntitiesOnly(startNanos, eye);
            return;
        }
        if (pending != null) {
            advanceProjection(pending, startNanos, eye, updateEntities, deadlineNanos);
            return;
        }

        long scanGeneration = invalidationGeneration.get();
        boolean projectionInvalidated = reuseInvalidated || lastCommittedGeneration != scanGeneration;
        schedule.beginBlockPass();
        blockPasses++;
        if (dissolve.retireComplete(blockPasses)) {
            close();
            return;
        }
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
        if (!renderModeChanged && !dissolve.isActive() && canReuseProjection(eye, stableResample, localDirty)) {
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
        FidelityPortalExtension fidelity = fidelityExtension();
        LodPolicy portalLod = portalLod(fidelity);
        viewFrustum.setLodPolicy(portalLod);
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
        LodPolicy observerLod = viewFrustum.fittedCoarse() ? portalLod.withMergeRuns() : portalLod;
        if (portal.isBlackoutBackground()) {
            blackout.beginPass(portal.getBlackoutColor());
        } else {
            blackout.disable();
        }
        boolean buriedCellCulling = renderMode.usesBuriedCellCulling();
        boolean buriedCellCullingChanged = sampler.setBuriedCellCullingPass(buriedCellCulling);

        if (!firstProjectionDone) {
            Wormholes.v("[Projector] portal=" + portal.getName() + " observer=" + observer.getName()
                + " first frustum: faceCount=" + next.getFaceCount() + " region=" + formatBox(next.getRegion())
                + " depth=" + depthBlocks + " requestedDepth=" + portalDepth
                + " fittedLateral=" + viewFrustum.fittedLateral()
                + " candidateWork=" + viewFrustum.fittedCandidateWork()
                + " lateralPad=" + portal.getNetworkViewLateralPad()
                + " aperturePadding=" + Settings.PROJECTION_APERTURE_PADDING_BLOCKS);
        }

        boolean scheduledContentResample = schedule.consumeForcedResample(stableResample);
        long destinationRevision = destination.destView.getRevision();
        boolean destinationSamplesStale = shouldInvalidateDestinationContentSamples(
            scheduledContentResample, renderModeChanged, buriedCellCullingChanged,
            sampler.recursiveSamplesCached())
            || sampleMemo.destinationStale(destinationRevision, destWorld != null,
                sinceVersion -> schedule.destinationDirty(destWorld, destinationOriginX, destinationOriginZ, sinceVersion))
            || sampleMemo.destinationOverBudget(sampleMemoBudget(viewFrustum.fittedCandidateWork()));
        if (destinationSamplesStale) {
            sampler.clearRecursivePortals();
            sampleMemo.clearDestinationSamples();
            sampleMemo.refreshDestination(destinationRevision);
        }
        sampler.resetRecursiveSamplesCached();
        boolean localContentResample = scheduledContentResample || renderModeChanged;
        boolean localSamplesStale = sampleMemo.refreshLocal(localContentResample, localDirty, destination.localView.getRevision(),
            sampleMemoBudget(viewFrustum.fittedCandidateWork()));
        sampleMemo.expandLocalRegionRect(next.getRegion());
        sampleMemo.markLocalScanned();

        boolean forceFullSend = initialFullSendPassesRemaining > 0;
        boolean blockEntities = FidelitySettings.blockEntities && (fidelity == null || fidelity.effectiveBlockEntities());
        long presentationRevision = presentationRevision(eye, rtpTarget, buriedCellCulling, observerLod, blockEntities);
        boolean contentInvalidated = projectionInvalidated || destinationSamplesStale || localSamplesStale
            || presentationRevision != lastPresentationRevision;
        boolean reuseStableContent = viewCameraMoved && !contentInvalidated
            && !scheduledContentResample && !renderModeChanged;
        if (contentInvalidated) {
            cellScan.invalidateContent();
        }
        boolean forceStableCellResample = contentInvalidated || shouldForceCellResample(
            scheduledContentResample, renderModeChanged, viewCameraMoved && !reuseStableContent);
        sampler.resetRemoteSampleCount();
        lastBlockChanges = 0;
        lastClaimConflicts = 0;
        lastWinnerChanges = 0;
        lastClaimReverts = 0;
        ViewPlate plate = rtpTarget == null ? acquirePlate(eye, buriedCellCulling, destinationRevision) : null;
        lastPassUsedPlate = plate != null;
        PendingProjection frame = new PendingProjection(eye, next, depthBlocks, viewFrustum.fittedCoarse(),
            fidelity, renderMode, forceFullSend, presentationRevision, destinationRevision,
            destination.localView, destination.destView, destination.destAnchor, scanContextRevision(),
            scanGeneration);
        if (firstProjectionDone && !projectionInvalidated && !dissolve.isActive()
            && !forceStableCellResample && !forceFullSend && !destinationSamplesStale && !localSamplesStale
            && presentationRevision == lastPresentationRevision && cellScan.canResumeOcclusion(destination, eye, next)) {
            long scanStarted = System.nanoTime();
            cellScan.resumeOcclusion();
            lastScanNanos = System.nanoTime() - scanStarted;
            occlusionResumes++;
            completeProjection(frame, startNanos, updateEntities);
        } else {
            cellScan.begin(destination, rtpTarget, eye, next, depthBlocks, forceStableCellResample, forceFullSend,
                viewCameraMoved, buriedCellCulling, renderMode, plate, blockEntities, observerLod);
            pendingProjection = frame;
            advanceProjection(frame, startNanos, eye, updateEntities, deadlineNanos);
        }
    }

    private void advanceProjection(PendingProjection frame, long startNanos, Location eye,
                                   boolean updateEntities, long deadlineNanos) {
        long scanStarted = System.nanoTime();
        boolean ready = cellScan.advance(deadlineNanos);
        lastScanNanos = System.nanoTime() - scanStarted;
        scanSlices++;
        if (ready) {
            completeProjection(frame, startNanos, updateEntities);
        } else if (updateEntities) {
            updateEntitiesOnly(startNanos, eye);
        } else {
            recordProjectTime(startNanos);
        }
    }

    private void completeProjection(PendingProjection frame, long startNanos, boolean updateEntities) {
        long finalizeStarted = System.nanoTime();
        if (!matchesPendingContext(frame, observer.getEyeLocation(), rtpProjectionTarget)) {
            cancelPendingProjection();
            recordProjectTime(startNanos);
            return;
        }
        Location eye = frame.eye();
        Frustum4D next = frame.frustum();
        double depthBlocks = frame.depthBlocks();
        FidelityPortalExtension fidelity = frame.fidelity();
        boolean forceFullSend = frame.forceFullSend();
        ProjectionRenderMode renderMode = frame.renderMode();
        long destinationRevision = frame.destinationRevision();
        long presentationRevision = frame.presentationRevision();
        if (!firstProjectionDone && !dissolve.isRetiring()) {
            dissolve.beginAdmit(blockPasses, FidelitySettings.dissolveTicks);
        }
        double admitted = dissolve.admittedFraction(blockPasses);
        if (admitted < 1.0D) {
            cellScan.invalidateOcclusionContinuation();
            double clearance = portalPlaneClearance(portal.getStructure().getArea(), portal.getFrame());
            Direction dissolveNormal = portal.getFrame().getNormal();
            double originNormal = axisValueOf(portal.getOrigin().getX(), portal.getOrigin().getY(), portal.getOrigin().getZ(), dissolveNormal);
            DissolveSchedule.filter(cellScan.claims(), admitted, depthBlocks + clearance, key -> Math.abs(
                axisValueOf(ProjectionCellKey.unpackX(key) + 0.5D, ProjectionCellKey.unpackY(key) + 0.5D,
                    ProjectionCellKey.unpackZ(key) + 0.5D, dissolveNormal) - originNormal));
        }

        if (!activeGuard.getAsBoolean()) {
            close();
            return;
        }
        if (discardRequested) {
            discard();
            return;
        }

        AtmosphereMode atmosphereMode = fidelity == null
            ? FidelitySettings.atmosphereModeDefault
            : fidelity.effectiveAtmosphereMode();
        ProjectorBlackoutMesh.Result blackoutMesh = cellScan.blackoutMesh();
        boolean displayReady = blackoutMesh.fallback()
            ? blackoutDisplayRenderer.prepareEmpty()
            : blackoutDisplayRenderer.prepare(observer, blackoutMesh.panels(),
                blackoutShell(atmosphereMode, cellScan.blackoutData()), depthBlocks);
        if (!displayReady) {
            cellScan.dropBlackoutDisplay();
            blackoutDisplayRenderer.prepareEmpty();
        }

        World submitWorld = destination.localWorld;
        noteClaimWorld(submitWorld);
        boolean sourceLighting = FidelitySettings.skyLight && atmosphereMode.promotesSkyLight();
        boolean lightingPass = (Settings.LIGHTING_FIDELITY || sourceLighting) && schedule.lightingUpdatePass(firstProjectionDone);
        ProjectionClaimArbiter.ClaimUpdateResult claimResult = claimArbiter.submitDelta(observer, portal, submitWorld,
            cellScan.claimDelta(), Math.abs(cellScan.eyeDot()), lightingPass, sourceLighting);
        lastBlockChanges = claimResult.getBlockChanges();
        lastClaimConflicts = claimResult.getConflicts();
        lastWinnerChanges = claimResult.getWinnerChanges();
        lastClaimReverts = claimResult.getReverts();
        lastRenderedCells = cellScan.claims().size();
        driveAtmosphere(submitWorld, atmosphereMode, !firstProjectionDone
            || claimResult.getBlockChanges() > 0 || claimResult.getReverts() > 0 || claimResult.getWinnerChanges() > 0);
        noteAcoustics(fidelity);
        if (forceFullSend) {
            blockEntityLayer.invalidateSent();
        }
        blockEntityLayer.update(cellScan.blockEntities(), destination.localView::sampleBlockEntity);

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
        completedScans++;
        pendingProjection = null;
        lastFinalizeNanos = System.nanoTime() - finalizeStarted;

        firstProjectionDone = true;
        lastRenderMode = renderMode;
        lastPresentationRevision = presentationRevision;
        lastCommittedGeneration = frame.invalidationGeneration();
        rememberCamera(eye);
        reuseInvalidated = invalidationGeneration.get() != frame.invalidationGeneration();
    }

    private boolean matchesPendingContext(PendingProjection frame, Location eye, RtpProjectionTarget rtpTarget) {
        if (frame.invalidationGeneration() != invalidationGeneration.get()
            || frame.contextRevision() != scanContextRevision()
            || frame.localView() != destination.localView || frame.destinationView() != destination.destView
            || frame.destinationAnchor() != destination.destAnchor) {
            return false;
        }
        FidelityPortalExtension fidelity = fidelityExtension();
        LodPolicy lod = portalLod(fidelity);
        if (frame.coarse()) {
            lod = lod.withMergeRuns();
        }
        boolean blockEntities = FidelitySettings.blockEntities && (fidelity == null || fidelity.effectiveBlockEntities());
        return frame.presentationRevision() == presentationRevision(eye, rtpTarget,
            portal.getRenderMode().usesBuriedCellCulling(), lod, blockEntities);
    }

    private long scanContextRevision() {
        long revision = mix(portal.getStructure().getRevision(), portal.getRenderMode().ordinal());
        revision = mix(revision, portal.isBlackoutBackground() ? 1L : 0L);
        revision = mix(revision, portal.getBlackoutColor() == null ? -1L : portal.getBlackoutColor().ordinal());
        revision = mix(revision, Double.doubleToLongBits(Settings.NEAR_PLANE_PADDING));
        revision = mix(revision, Double.doubleToLongBits(Settings.FRUSTUM_CULLING_RATIO));
        revision = mix(revision, Double.doubleToLongBits(Settings.PROJECTION_OCCLUSION_REVEAL_MARGIN_DEGREES));
        revision = mix(revision, Settings.PROJECTION_MAX_PROJECTED_CELLS);
        revision = mix(revision, Settings.PROJECTION_RECURSIVE_PORTAL_DEPTH);
        return mix(revision, rtpProjectionTarget == null ? -1L : rtpProjectionTarget.routeRevision());
    }

    private void cancelPendingProjection() {
        cellScan.cancelPending();
        pendingProjection = null;
        reuseInvalidated = true;
        schedule.invalidateDestination();
    }

    private void recordProjectTime(long startNanos) {
        lastProjectNanos = System.nanoTime() - startNanos;
        WormholesTelemetry.addRenderNanos(lastProjectNanos);
    }

    public void finishBlackoutDisplayFrame() {
        if (!closed) {
            blackoutDisplayRenderer.finish(observer);
        }
    }

    /** Sends queued block-entity data after the frame's block changes; returns the packets sent. */
    public int flushBlockEntities(int budget) {
        if (closed || budget <= 0 || !blockEntityLayer.hasPending()) {
            return 0;
        }
        return blockEntityLayer.flush(observer, budget);
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
        cellScan.updateEntityOcclusionEye(eye, destination, projectionLocalFrame, projectionRemoteFrame);
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
                projectionLocalFrame, projectionRemoteFrame, cellScan.entityOcclusion());
            return;
        }
        if (destination.dest != null) {
            entityRenderer.apply(observer, portal, destination.dest, frustum, depthBlocks, projectionLocalFrame,
                projectionRemoteFrame, mirrorRotationQuarterTurns, cellScan.entityOcclusion());
            return;
        }
        if (destView instanceof RemoteWorldView remoteWorldView) {
            double remoteOriginX = destAnchor.getOrigin().getX();
            double remoteOriginY = destAnchor.getOrigin().getY();
            double remoteOriginZ = destAnchor.getOrigin().getZ();
            entityRenderer.applyRemote(observer, portal, remoteOriginX, remoteOriginY, remoteOriginZ,
                remoteWorldView, frustum, depthBlocks, projectionLocalFrame, projectionRemoteFrame,
                cellScan.entityOcclusion());
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

    private void noteAcoustics(FidelityPortalExtension fidelity) {
        AcousticsBridge bridge = FidelitySubsystem.acoustics();
        if (bridge == null || portal.getId() == null || destination.destAnchor == null) {
            return;
        }
        AcousticsProfile profile = fidelity == null ? FidelitySettings.acousticsProfileDefault : fidelity.effectiveAcousticsProfile();
        Location center = portal.getCenter();
        if (center == null) {
            return;
        }
        long now = System.currentTimeMillis();
        World destWorld = destination.destWorld;
        if (destWorld != null) {
            bridge.noteDestination(portal.getId(), destWorld.getUID(), destination.originX, destination.originY, destination.originZ,
                center.getX(), center.getY(), center.getZ(), profile, destWorld.getEnvironment(), destWorld.hasStorm(), now);
            return;
        }
        if (destination.destAnchor instanceof RemotePortal remote && portal.getTunnel() instanceof UniversalTunnel universal) {
            bridge.noteRemoteDestination(portal.getId(), universal.getServerName(), remote.getId(),
                destination.originX, destination.originY, destination.originZ,
                center.getX(), center.getY(), center.getZ(), profile, now);
        }
    }

    private BlockData blackoutShell(AtmosphereMode mode, BlockData blackoutData) {
        World destWorld = destination.destWorld;
        if (destWorld == null || !FogPlatePolicy.applies(FidelitySettings.fogPlate, mode)) {
            return blackoutData;
        }
        return FogPlatePolicy.shell(destWorld.getEnvironment(), blackoutData);
    }

    private void driveAtmosphere(World submitWorld, AtmosphereMode mode, boolean claimsChanged) {
        if (portal.getId() == null) {
            return;
        }
        if (FidelitySettings.biomeTint && mode.tintsBiomes()) {
            atmosphere.update(observer, portal.getId(), submitWorld, cellScan.claims(), destination.destView,
                claimArbiter, claimsChanged);
        } else {
            atmosphere.disable(observer, portal.getId(), submitWorld, claimArbiter);
        }
        if (!FidelitySettings.weather || !mode.relaysWeather()) {
            return;
        }
        boolean storm;
        boolean thunder;
        World destWorld = destination.destWorld;
        if (destWorld != null) {
            storm = destWorld.hasStorm();
            thunder = destWorld.isThundering();
        } else if (destination.remoteView instanceof RemoteWorldView remote) {
            storm = remote.hasStorm();
            thunder = remote.isThundering();
        } else {
            return;
        }
        String biome = destination.destView.sampleBiome((int) Math.floor(destination.originX),
            (int) Math.floor(destination.originY), (int) Math.floor(destination.originZ));
        WeatherRelay.Burst burst = weather.plan(storm, thunder, biome, System.nanoTime() / 50_000_000L);
        if (burst != null) {
            weather.spawn(observer, cellScan.claims(), burst, weatherRandom);
        }
    }

    /**
     * The plate this portal shares with every observer standing on the same side of it. Only
     * portal-scoped inputs may reach the revision: the coarsening {@link ProjectorViewFrustum#fit}
     * applies for one observer's eye and client view distance stays on that observer's scan, or two
     * observers of one portal would invalidate each other's plate every frame.
     */
    private ViewPlate acquirePlate(Location eye, boolean buriedCellCulling, long destinationRevision) {
        ViewPlateCache cache = plateCache;
        if (cache == null || !FidelitySettings.sharedPlate || portal.getId() == null) {
            return null;
        }
        PortalFrame localFrame = portal.getFrame();
        double localOriginX = portal.getOrigin().getX();
        double localOriginY = portal.getOrigin().getY();
        double localOriginZ = portal.getOrigin().getZ();
        Direction facing = localFrame.getNormal();
        boolean eyeFrontSide = ((eye.getX() - localOriginX) * facing.x()
            + (eye.getY() - localOriginY) * facing.y()
            + (eye.getZ() - localOriginZ) * facing.z()) >= 0.0D;
        boolean mirrorMode = destination.mirrorMode;
        int quarterTurns = destination.mirrorRotationQuarterTurns;
        PortalFrame remoteFrame = mirrorMode ? localFrame.flipNormal() : destination.destAnchor.getFrame();
        double remoteOriginX = mirrorMode ? localOriginX : destination.originX;
        double remoteOriginY = mirrorMode ? localOriginY : destination.originY;
        double remoteOriginZ = mirrorMode ? localOriginZ : destination.originZ;
        int depth = portal.getNetworkViewDepth();
        int lateral = portal.getNetworkViewLateralPad();
        double aperturePadding = Settings.PROJECTION_APERTURE_PADDING_BLOCKS;
        FidelityPortalExtension fidelity = fidelityExtension();
        LodPolicy lod = portalLod(fidelity);
        boolean blockEntities = FidelitySettings.blockEntities && (fidelity == null || fidelity.effectiveBlockEntities());
        long transformRevision = plateTransformRevision(localFrame, remoteFrame, localOriginX, localOriginY, localOriginZ,
            remoteOriginX, remoteOriginY, remoteOriginZ, depth, lateral, aperturePadding, buriedCellCulling, lod, blockEntities);
        ViewPlateKey key = new ViewPlateKey(portal.getId(), destination.destView, eyeFrontSide, quarterTurns);
        return cache.current(key, destinationRevision, transformRevision, () -> {
            ProjectionWorldView plateView = destination.plateView();
            World destWorld = plateView.getWorld();
            ViewPlateBuilder.Execution execution = destWorld == null || viewProvider.usesRegionSnapshots()
                ? ViewPlateBuilder.Execution.async()
                : ViewPlateBuilder.Execution.region(destWorld, ((int) Math.floor(remoteOriginX)) >> 4, ((int) Math.floor(remoteOriginZ)) >> 4);
            long trackerVersion = Wormholes.projectionChangeTracker == null
                ? Long.MIN_VALUE : Wormholes.projectionChangeTracker.currentVersion();
            ViewPlateBuilder.Request request = new ViewPlateBuilder.Request(key, portal, plateView, localFrame, remoteFrame,
                localOriginX, localOriginY, localOriginZ, remoteOriginX, remoteOriginY, remoteOriginZ,
                mirrorMode, quarterTurns, depth, lateral, aperturePadding, buriedCellCulling, sampler.air(), lod,
                blockEntities, destinationRevision, transformRevision, trackerVersion, null);
            return ViewPlateBuilder.job(request, execution);
        });
    }

    /** The portal's own level of detail, before any per-observer coarsening. */
    private static LodPolicy portalLod(FidelityPortalExtension fidelity) {
        return LodPolicy.current(fidelity == null ? null : fidelity.effectiveLodProfile());
    }

    private long presentationRevision(Location eye, RtpProjectionTarget rtpTarget, boolean buriedCellCulling,
                                      LodPolicy lod, boolean blockEntities) {
        PortalFrame localFrame = portal.getFrame();
        PortalFrame remoteFrame = rtpTarget != null ? rtpTarget.frame()
            : destination.mirrorMode ? localFrame.flipNormal() : destination.destAnchor.getFrame();
        double localOriginX = portal.getOrigin().getX();
        double localOriginY = portal.getOrigin().getY();
        double localOriginZ = portal.getOrigin().getZ();
        double remoteOriginX = destination.mirrorMode ? localOriginX : destination.originX;
        double remoteOriginY = destination.mirrorMode ? localOriginY : destination.originY;
        double remoteOriginZ = destination.mirrorMode ? localOriginZ : destination.originZ;
        Direction facing = localFrame.getNormal();
        boolean eyeFrontSide = ((eye.getX() - localOriginX) * facing.x()
            + (eye.getY() - localOriginY) * facing.y()
            + (eye.getZ() - localOriginZ) * facing.z()) >= 0.0D;
        long revision = plateTransformRevision(localFrame, remoteFrame, localOriginX, localOriginY, localOriginZ,
            remoteOriginX, remoteOriginY, remoteOriginZ, portal.getNetworkViewDepth(), portal.getNetworkViewLateralPad(),
            Settings.PROJECTION_APERTURE_PADDING_BLOCKS, buriedCellCulling, lod, blockEntities);
        revision = mix(revision, destination.mirrorRotationQuarterTurns);
        return mix(revision, eyeFrontSide ? 1L : 0L);
    }

    FidelityPortalExtension fidelityExtension() {
        if (portal instanceof LocalPortal local) {
            return local.extension(FidelityPortalExtension.class);
        }
        return null;
    }

    static long plateTransformRevision(PortalFrame localFrame,
                                       PortalFrame remoteFrame,
                                       double localOriginX,
                                       double localOriginY,
                                       double localOriginZ,
                                       double remoteOriginX,
                                       double remoteOriginY,
                                       double remoteOriginZ,
                                       int depth,
                                       int lateral,
                                       double aperturePadding,
                                       boolean buriedCellCulling,
                                       LodPolicy lod,
                                       boolean blockEntities) {
        long hash = 1125899906842597L;
        hash = mix(hash, localFrame.getNormal().ordinal());
        hash = mix(hash, localFrame.getRight().ordinal());
        hash = mix(hash, localFrame.getUp().ordinal());
        hash = mix(hash, remoteFrame.getNormal().ordinal());
        hash = mix(hash, remoteFrame.getRight().ordinal());
        hash = mix(hash, remoteFrame.getUp().ordinal());
        hash = mix(hash, Double.doubleToLongBits(localOriginX));
        hash = mix(hash, Double.doubleToLongBits(localOriginY));
        hash = mix(hash, Double.doubleToLongBits(localOriginZ));
        hash = mix(hash, Double.doubleToLongBits(remoteOriginX));
        hash = mix(hash, Double.doubleToLongBits(remoteOriginY));
        hash = mix(hash, Double.doubleToLongBits(remoteOriginZ));
        hash = mix(hash, depth);
        hash = mix(hash, lateral);
        hash = mix(hash, Double.doubleToLongBits(aperturePadding));
        hash = mix(hash, buriedCellCulling ? 1L : 0L);
        hash = mix(hash, lod.mergeRuns() ? 1L : 0L);
        hash = mix(hash, lod.distanceBlocks());
        hash = mix(hash, lod.detailCutoffBlocks());
        hash = mix(hash, blockEntities ? 1L : 0L);
        return hash;
    }

    private static long mix(long hash, long value) {
        long mixed = (hash ^ value) * 0x100000001B3L;
        return mixed ^ (mixed >>> 29);
    }

    private int sampleMemoBudget(long fittedCandidateWork) {
        return ProjectorSampleMemo.budgetFor(lastRenderedCells, fittedCandidateWork);
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

    public static boolean projectsBehindPortalPlane(double signedCellDistance, boolean eyeFrontSide, double portalPlaneClearance) {
        if (Math.abs(signedCellDistance) <= portalPlaneClearance) {
            return false;
        }
        boolean cellFrontSide = signedCellDistance >= 0.0D;
        return cellFrontSide != eyeFrontSide;
    }

    public static double portalPlaneClearance(AxisAlignedBB area, PortalFrame frame) {
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

    public static int minBlockForCenter(double centerMin) {
        return (int) Math.ceil(centerMin - 0.500001D);
    }

    public static int maxBlockForCenter(double centerMax) {
        return (int) Math.floor(centerMax - 0.499999D);
    }

    private boolean canReuseProjection(Location eye, boolean stableResample, boolean localDirty) {
        if (reuseInvalidated || lastCommittedGeneration != invalidationGeneration.get()) {
            return false;
        }
        if (cellScan.hasUnresolvedOcclusion()) {
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

    static boolean shouldForceCellResample(boolean scheduledContentResample,
                                           boolean renderModeChanged,
                                           boolean viewCameraMoved) {
        return scheduledContentResample || renderModeChanged || viewCameraMoved;
    }

    static boolean shouldInvalidateDestinationContentSamples(boolean scheduledContentResample,
                                                              boolean renderModeChanged,
                                                              boolean buriedCellCullingChanged,
                                                              boolean recursiveSamplesCached) {
        return scheduledContentResample || renderModeChanged || buriedCellCullingChanged || recursiveSamplesCached;
    }

    private void rememberCamera(Location eye) {
        lastEyeX = eye.getX();
        lastEyeY = eye.getY();
        lastEyeZ = eye.getZ();
        hasCameraSnapshot = true;
        reuseInvalidated = false;
    }

    private void invalidateRtpDestinationState() {
        cancelPendingProjection();
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
            restoreLocalBlockEntities();
        } else {
            blackoutDisplayRenderer.discard();
            entityRenderer.discard(observer);
            blockEntityLayer.clear();
        }
        cellScan.clear();
        pendingProjection = null;
        lastRenderedCells = 0;
    }

    private void restoreLocalBlockEntities() {
        ProjectionWorldView localView = destination.localView;
        if (localView == null) {
            blockEntityLayer.clear();
            return;
        }
        blockEntityLayer.retireAll(localView::sampleBlockEntity);
        if (!blockEntityLayer.hasPending()) {
            return;
        }
        Wormholes plugin = Wormholes.instance;
        boolean scheduled = plugin != null && FoliaScheduler.runEntity(plugin, observer,
            () -> blockEntityLayer.flush(observer, Integer.MAX_VALUE), 1L);
        if (!scheduled) {
            blockEntityLayer.flush(observer, Integer.MAX_VALUE);
        }
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
        pendingProjection = null;
        lastRenderedCells = 0;
        blackoutDisplayRenderer.discard();
        entityRenderer.discard(observer);
        blockEntityLayer.clear();
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

    private record PendingProjection(Location eye, Frustum4D frustum, double depthBlocks, boolean coarse,
                                     FidelityPortalExtension fidelity, ProjectionRenderMode renderMode,
                                     boolean forceFullSend, long presentationRevision, long destinationRevision,
                                     ProjectionWorldView localView, ProjectionWorldView destinationView,
                                     IPortal destinationAnchor, long contextRevision, long invalidationGeneration) {
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
