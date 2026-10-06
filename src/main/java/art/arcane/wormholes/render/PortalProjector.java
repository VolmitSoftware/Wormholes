package art.arcane.wormholes.render;

import org.bukkit.entity.Entity;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import art.arcane.wormholes.util.BukkitGeometry;
import art.arcane.wormholes.render.BukkitProjectorBlocks;
import org.bukkit.block.data.BlockData;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation.EntityAnimationType;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.service.WormholesTelemetry;
import art.arcane.wormholes.platform.BukkitOpticsScheduler;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.RemotePortal;
import art.arcane.wormholes.portal.UniversalTunnel;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.portal.rtp.RtpProjectionGeometry;
import art.arcane.wormholes.portal.rtp.RtpProjectionView;
import art.arcane.optics.fidelity.AtmosphereChannel;
import art.arcane.optics.fidelity.AtmosphereMode;
import art.arcane.optics.fidelity.AcousticsBridge;
import art.arcane.optics.fidelity.AcousticsProfile;
import art.arcane.wormholes.render.bedrock.ClientProfileService;
import art.arcane.optics.fidelity.ProjectedBlockEntityLayer;
import art.arcane.wormholes.render.lod.DissolveSchedule;
import art.arcane.optics.volume.LodPolicy;
import art.arcane.optics.plate.ViewPlate;
import art.arcane.optics.plate.ViewPlateCache;
import art.arcane.wormholes.render.view.ProjectionEntityView;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.render.view.ProjectionWorldViewProvider;
import art.arcane.wormholes.render.view.RemoteWorldView;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;

import art.arcane.wormholes.portal.ProjectorViewSettings;
import art.arcane.optics.claim.ProjectedBlockClaim;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.occlusion.LocalOcclusionArbiter;
import art.arcane.optics.recursion.EntityPath;
import art.arcane.optics.recursion.RecursiveEndpoints;
import art.arcane.optics.scan.CellScan;
import art.arcane.optics.scan.ProjectorCommitLatency;
import art.arcane.optics.scan.ProjectorFrustumFailures;
import art.arcane.optics.scan.ProjectorPassRevision;
import art.arcane.optics.scan.ProjectorResampleReasons;
import art.arcane.optics.scan.ProjectorSampleMemo;
import art.arcane.optics.scan.ProjectorSampler;
import art.arcane.optics.scan.ResampleSchedule;
import art.arcane.optics.volume.GazeScheduler;
import art.arcane.optics.volume.ViewVolume;
import art.arcane.optics.volume.ProjectionVolume;
public final class PortalProjector {
    private static final long DIAG_LOG_INTERVAL_PASSES = 50L;

    private final ILocalPortal portal;
    private final Player observer;
    private final UUID observerId;
    private final UUID localWorldId;
    private final ProjectionClaimArbiter claimArbiter;
    private final UUID endSurfaceOwner;
    private final Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> endSurfaceClaims;
    private ProjectedBlockClaim<BlockData, ProjectionWorldView> endSurfaceAir;
    private boolean endSurfaceActive;
    private final ProjectionWorldViewProvider viewProvider;
    private final BooleanSupplier activeGuard;
    private final ProjectorDestination destination;
    private final ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> sampleMemo;
    private final ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler;
    private final ProjectorBlackoutSeal blackout;
    private final ProjectorViewFrustum viewFrustum;
    private final ResampleSchedule schedule;
    private final CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> cellScan;
    private final ProjectorFrustumFailures frustumFailures;
    private final ProjectedEntityRenderer entityRenderer;
    private final RecursiveEndpoints<World, ILocalPortal> entityRecursivePortals = BukkitProjectorPortalAccess.create();
    private final ViewPlateCache<BlockData, World> plateCache;
    private final AtmosphereChannel<BlockData, ProjectionWorldView> atmosphere = new AtmosphereChannel<>();
    private final ProjectorWeather weather = new ProjectorWeather();
    private final ProjectedBlockEntityLayer<Player> blockEntityLayer = new ProjectedBlockEntityLayer<Player>(WormholesTelemetry.metrics());
    private final DissolveSchedule dissolve = new DissolveSchedule();
    private final ProjectorCommitLatency commitLatency = new ProjectorCommitLatency();
    private final ProjectorResampleReasons resampleReasons = new ProjectorResampleReasons();
    private final LongOpenHashSet displacedClaimKeys = new LongOpenHashSet(16);
    private final LongOpenHashSet restoredClaimKeys = new LongOpenHashSet(16);
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
        this(portal, observer, claimArbiter, viewProvider, activeGuard, BukkitEntityRegistryHost.occlusion(BukkitEntityRegistryHost.PLUGIN_VISIBILITY), null);
    }

    public PortalProjector(ILocalPortal portal,
                           Player observer,
                           ProjectionClaimArbiter claimArbiter,
                           ProjectionWorldViewProvider viewProvider,
                           BooleanSupplier activeGuard,
                           LocalOcclusionArbiter<Player, Entity> localEntityOcclusion,
                           ViewPlateCache<BlockData, World> plateCache) {
        this.plateCache = plateCache;
        this.portal = portal;
        this.observer = observer;
        this.observerId = observer.getUniqueId();
        World constructionWorld = portal.getWorld();
        this.localWorldId = constructionWorld == null ? null : constructionWorld.getUID();
        this.claimArbiter = claimArbiter;
        boolean endExit = portal.getDimensionalPortalKind() == DimensionalPortalKind.END_EXIT;
        this.endSurfaceOwner = endExit ? UUID.randomUUID() : null;
        this.endSurfaceClaims = endExit ? new Long2ObjectOpenHashMap<>(32) : null;
        this.viewProvider = viewProvider;
        this.activeGuard = activeGuard;
        this.entityRenderer = new ProjectedEntityRenderer(localEntityOcclusion, portal.getId());
        this.destination = new ProjectorDestination(portal, viewProvider);
        this.sampleMemo = BukkitProjectorBlocks.memo();
        this.sampler = BukkitProjectorBlocks.sampler(sampleMemo, BukkitProjectorPortalAccess.create(), destination::liveView);
        this.blackout = new ProjectorBlackoutSeal();
        this.viewFrustum = new ProjectorViewFrustum();
        this.schedule = new ResampleSchedule(() -> ProjectorViewSettings.viewCadence(portal), () -> Wormholes.projectionChangeTracker,
            PortalProjector::resampleCadence);
        this.cellScan = BukkitProjectorBlocks.scan(portal, sampler, sampleMemo, blackout);
        this.frustumFailures = new ProjectorFrustumFailures(WormholesTelemetry.metrics());
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
                ProjectorPlates.retireTarget(plateCache, portal, previous, null);
            }
            return;
        }
        if (target.requiresDestinationInvalidation(previous)) {
            invalidateRtpDestinationState();
            ProjectorPlates.retireTarget(plateCache, portal, previous, target);
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
        return entityRenderer.getSpoofedCount();
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
            + " occlusionVerdictHits=" + cellScan.occlusionVerdictHits()
            + " resumeTraced=" + cellScan.resumeTraced()
            + " resumeResolved=" + cellScan.resumeResolved()
            + " unresolvedBefore=" + cellScan.resumeUnresolvedBefore()
            + " unresolvedAfter=" + cellScan.unresolvedOcclusionCells()
            + " occlusionBudgetExhausted=" + cellScan.occlusionBudgetExhausted()
            + " remoteSamples=" + sampler.remoteSampleCount()
            + " emptyCellSkips=" + cellScan.emptyCellSkips()
            + " reuseSkips=" + lastReuseSkips
            + " scanSlices=" + scanSlices
            + " completedScans=" + completedScans
            + " commitLatencyTicks=" + commitLatency.describe()
            + " scanPending=" + hasPendingScan()
            + " scanNanos=" + lastScanNanos
            + " finalizeNanos=" + lastFinalizeNanos
            + " maskAir=" + cellScan.maskedCells()
            + " plate=" + lastPassUsedPlate
            + " plateHits=" + cellScan.plateHits()
            + " blackoutClaims=" + cellScan.blackoutClaims()
            + " heldClaims=" + cellScan.heldClaims()
            + " hiddenHolds=" + cellScan.hiddenHolds()
            + " coneHolds=" + cellScan.coneHolds()
            + " heldEvictions=" + cellScan.heldEvictions()
            + " losingClaims=" + cellScan.losingClaims()
            + " remoteSections=" + cellScan.remoteFootprint().size()
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
        try {
            projectPass(updateBlocks, updateEntities, deadlineNanos);
        } catch (RuntimeException failure) {
            releaseEndSurface();
            if (endSurfaceOwner != null) {
                cancelPendingProjection();
            }
            throw failure;
        }
    }

    private void projectPass(boolean updateBlocks, boolean updateEntities, long deadlineNanos) {
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
        ProjectorDestination.Outcome outcome = destination.resolve(observer, observer.getWorld(), rtpTarget, 0);
        if (outcome == ProjectorDestination.Outcome.CLOSE) {
            close();
            return;
        }
        if (outcome == ProjectorDestination.Outcome.WAIT) {
            releaseEndSurface();
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
        ProjectionRenderMode renderMode = portal.getRenderMode();
        boolean renderModeChanged = renderMode != lastRenderMode;
        boolean viewCameraMoved = requiresViewCellResample(renderMode, hasCameraSnapshot,
            eye.getX(), eye.getY(), eye.getZ(), lastEyeX, lastEyeY, lastEyeZ);
        double destinationOriginX = destination.originX;
        double destinationOriginZ = destination.originZ;
        UUID destWorldId = destWorld == null ? null : destWorld.getUID();
        boolean stableResample = schedule.stableResample(firstProjectionDone, destination.destView.getRevision(),
            destination.destView instanceof RemoteWorldView, destWorldId, destinationOriginX, destinationOriginZ, cellScan.remoteFootprint());
        boolean localDirty = sampleMemo.localRegionDirty(destination.localView, localWorldId);
        if (localDirty) {
            cellScan.revokeConeHolds();
        }
        boolean holdsExposed = exposeLosingClaims();
        if (!renderModeChanged && !dissolve.isActive() && canReuseProjection(eye, stableResample, localDirty || holdsExposed)) {
            lastReuseSkips++;
            lastBlockChanges = 0;
            if (updateEntities) {
                updateEntitiesOnly(startNanos, eye);
            } else {
                recordProjectTime(startNanos);
            }
            logDiagnostics(false, 0, 0, cellScan.claims().size());
            return;
        }
        int resampleReasonMask = reuseBlockers(eye, projectionInvalidated, stableResample, localDirty, renderModeChanged);
        if (holdsExposed) {
            resampleReasonMask |= ProjectorResampleReasons.HOLDS_EXPOSED;
        }

        double portalDepth = portal.getNetworkViewDepth();
        FidelityPortalExtension fidelity = fidelityExtension();
        LodPolicy portalLod = portalLod(fidelity);
        viewFrustum.setLodPolicy(portalLod);
        ViewVolume next;
        try {
            next = viewFrustum.fit(observer, portal.getStructure(), portal.getFrame(), eye, portalDepth,
                portal.getNetworkViewLateralPad());
        } catch (RuntimeException ex) {
            noteFrustumFailure("block", ex);
            recordProjectTime(startNanos);
            return;
        }
        frustumFailures.recordSuccess();
        double depthBlocks = viewFrustum.fittedDepth();
        LodPolicy observerLod = viewFrustum.fittedCoarse() ? portalLod.withMergeRuns() : portalLod;
        if (portal.isBlackoutBackground()) {
            blackout.beginPass(portal.getBlackoutColor(),
                destWorld == null ? null : destWorld.getEnvironment(),
                fidelity == null ? FidelitySettings.atmosphereModeDefault : fidelity.effectiveAtmosphereMode());
        } else {
            blackout.disable();
        }
        boolean buriedCellCulling = renderMode.scanMode().buriedCellCulling();
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
        boolean recursiveSamplesCached = sampler.recursiveSamplesCached();
        boolean destinationContentStale = shouldInvalidateDestinationContentSamples(
            scheduledContentResample, renderModeChanged, buriedCellCullingChanged, recursiveSamplesCached);
        boolean destinationDirty = !destinationContentStale && sampleMemo.destinationStale(destinationRevision, destWorld != null,
            sinceVersion -> schedule.destinationUnaffectedThrough(destWorldId, destinationOriginX, destinationOriginZ, sinceVersion,
                cellScan.remoteFootprint()));
        boolean destinationOverBudget = !destinationContentStale && !destinationDirty
            && sampleMemo.destinationOverBudget(sampleMemoBudget(viewFrustum.fittedCandidateWork()));
        boolean destinationSamplesStale = destinationContentStale || destinationDirty || destinationOverBudget;
        if (destinationContentStale) {
            cellScan.dropHolds();
        }
        if (destinationContentStale && recursiveSamplesCached) {
            resampleReasonMask |= ProjectorResampleReasons.DEST_STALE_RECURSIVE;
        }
        if (destinationDirty) {
            resampleReasonMask |= ProjectorResampleReasons.DEST_STALE_DIRTY;
        }
        if (destinationOverBudget) {
            resampleReasonMask |= ProjectorResampleReasons.DEST_OVER_BUDGET;
        }
        if (destinationSamplesStale) {
            cellScan.restartRemoteFootprint();
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
        if (presentationRevision != lastPresentationRevision) {
            resampleReasonMask |= ProjectorResampleReasons.PRESENTATION;
        }
        resampleReasons.record(resampleReasonMask);
        ViewPlate<BlockData> plate = acquirePlate(eye, buriedCellCulling, destinationRevision, rtpTarget);
        lastPassUsedPlate = plate != null;
        PendingProjection frame = new PendingProjection(eye, next, depthBlocks, viewFrustum.fittedCoarse(),
            fidelity, renderMode, forceFullSend, presentationRevision, destinationRevision,
            destination.localView, destination.destView, destination.destAnchor, scanContextRevision(),
            scanGeneration);
        if (firstProjectionDone && !projectionInvalidated && !dissolve.isActive()
            && !forceStableCellResample && !forceFullSend && !destinationSamplesStale && !localSamplesStale
            && presentationRevision == lastPresentationRevision && cellScan.canResumeOcclusion(destination, BukkitGeometry.vector(eye), next)) {
            long scanStarted = System.nanoTime();
            cellScan.resumeOcclusion();
            lastScanNanos = System.nanoTime() - scanStarted;
            occlusionResumes++;
            completeProjection(frame, startNanos, updateEntities);
        } else {
            cellScan.begin(destination, rtpTarget == null ? null : rtpTarget.frame(), BukkitGeometry.vector(eye), next, depthBlocks, forceStableCellResample, forceFullSend,
                viewCameraMoved, renderMode.scanMode(), plate, blockEntities, observerLod);
            pendingProjection = frame;
            commitLatency.begin(startNanos);
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
        ViewVolume next = frame.frustum();
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
            double clearance = ProjectionVolume.portalPlaneClearance(portal.getStructure().getArea(), portal.getFrame());
            Face dissolveNormal = portal.getFrame().getNormal();
            double originNormal = axisValueOf(portal.getOrigin().getX(), portal.getOrigin().getY(), portal.getOrigin().getZ(), dissolveNormal);
            DissolveSchedule.filter(cellScan.claims(), admitted, depthBlocks + clearance, key -> Math.abs(
                axisValueOf(CellKeys.unpackX(key) + 0.5D, CellKeys.unpackY(key) + 0.5D,
                    CellKeys.unpackZ(key) + 0.5D, dissolveNormal) - originNormal));
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

        logDiagnostics(cellScan.enterCount() > 0 || cellScan.exitCount() > 0,
            cellScan.enterCount(), cellScan.exitCount(), cellScan.keptCount());

        updateEndSurface(destination.localView, submitWorld, Math.abs(cellScan.eyeDot()));
        cellScan.commit();
        commitLatency.commit(System.nanoTime());
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
            portal.getRenderMode().scanMode().buriedCellCulling(), lod, blockEntities);
    }

    private long scanContextRevision() {
        long revision = ProjectorPassRevision.mix(portal.getStructure().getRevision(), portal.getRenderMode().ordinal());
        revision = ProjectorPassRevision.mix(revision, portal.isBlackoutBackground() ? 1L : 0L);
        revision = ProjectorPassRevision.mix(revision, portal.getBlackoutColor() == null ? -1L : portal.getBlackoutColor().ordinal());
        revision = ProjectorPassRevision.mix(revision, Double.doubleToLongBits(Settings.NEAR_PLANE_PADDING));
        revision = ProjectorPassRevision.mix(revision, Double.doubleToLongBits(Settings.FRUSTUM_CULLING_RATIO));
        revision = ProjectorPassRevision.mix(revision, Double.doubleToLongBits(Settings.PROJECTION_OCCLUSION_REVEAL_MARGIN_DEGREES));
        revision = ProjectorPassRevision.mix(revision, Settings.PROJECTION_MAX_PROJECTED_CELLS);
        revision = ProjectorPassRevision.mix(revision, Settings.PROJECTION_RECURSIVE_PORTAL_DEPTH);
        return ProjectorPassRevision.mix(revision, rtpProjectionTarget == null ? -1L : rtpProjectionTarget.routeRevision());
    }

    private void cancelPendingProjection() {
        cellScan.cancelPending();
        commitLatency.cancel();
        pendingProjection = null;
        reuseInvalidated = true;
        schedule.invalidateDestination();
    }

    private void recordProjectTime(long startNanos) {
        lastProjectNanos = System.nanoTime() - startNanos;
        WormholesTelemetry.addRenderNanos(lastProjectNanos);
    }

    /** Sends queued block-entity data after the frame's block changes; returns the packets sent. */
    public int flushBlockEntities(int budget) {
        if (closed || budget <= 0 || !blockEntityLayer.hasPending()) {
            return 0;
        }
        return blockEntityLayer.flush(observer, budget, claimArbiter.output());
    }

    private void updateEntitiesOnly(long startNanos, Location eye) {
        if (destination.destAnchor == null || !firstProjectionDone || !cellScan.hasProjection()) {
            lastProjectNanos = System.nanoTime() - startNanos;
            WormholesTelemetry.addRenderNanos(lastProjectNanos);
            return;
        }

        double portalDepth = portal.getNetworkViewDepth();
        ViewVolume frustum;
        try {
            frustum = viewFrustum.fit(observer, portal.getStructure(), portal.getFrame(), eye, portalDepth,
                portal.getNetworkViewLateralPad());
        } catch (RuntimeException ex) {
            noteFrustumFailure("entity", ex);
            recordProjectTime(startNanos);
            return;
        }
        frustumFailures.recordSuccess();
        double depthBlocks = viewFrustum.fittedDepth();

        Frame localFrame = portal.getFrame();
        Frame remoteFrame = destination.mirrorMode ? localFrame.flipNormal() : destination.destAnchor.getFrame();
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
        Frame projectionLocalFrame = viewFrame(localFrame, eyeFrontSide);
        Frame projectionRemoteFrame = viewFrame(remoteFrame, eyeFrontSide);
        cellScan.updateEntityOcclusionEye(BukkitGeometry.vector(eye), destination, projectionLocalFrame, projectionRemoteFrame);
        updateProjectedEntities(frustum, depthBlocks, true, projectionLocalFrame, projectionRemoteFrame);
        lastProjectNanos = System.nanoTime() - startNanos;
        WormholesTelemetry.addRenderNanos(lastProjectNanos);
    }

    private void updateProjectedEntities(ViewVolume frustum,
                                         double depthBlocks,
                                         boolean hasVisibleProjection,
                                         Frame projectionLocalFrame,
                                         Frame projectionRemoteFrame) {
        IPortal destAnchor = destination.destAnchor;
        if (!hasVisibleProjection || destAnchor == null) {
            entityRenderer.close(observer);
            return;
        }
        ProjectionWorldView destView = destination.destView;
        OpticTransform transform = destination.mirrorMode
            ? OpticTransform.mirror(portal.getFrame(), portal.getOrigin(), QuarterTurn.of(destination.mirrorRotationQuarterTurns))
            : OpticTransform.between(projectionRemoteFrame, destAnchor.getOrigin(), projectionLocalFrame, portal.getOrigin());
        entityRenderer.prepareRecursiveProjection(destination.dest == null ? null : new EntityPath.Root<>(portal, destination.dest, transform,
            BukkitGeometry.vector(observer.getEyeLocation().toVector()), frustum, destination.dest.getWorld(),
            Settings.PROJECTION_RECURSIVE_PORTAL_DEPTH), entityRecursivePortals);
        if (viewProvider.usesRegionSnapshots() && destView instanceof ProjectionEntityView entityView) {
            entityRenderer.applySnapshot(observer, portal, destAnchor, entityView, frustum, depthBlocks, transform, cellScan.entityOcclusion());
        } else if (destination.dest != null) {
            entityRenderer.apply(observer, portal, destination.dest, frustum, depthBlocks, transform, cellScan.entityOcclusion());
        } else if (destView instanceof RemoteWorldView remoteWorldView) {
            entityRenderer.applyRemote(observer, portal, remoteWorldView, frustum, depthBlocks, transform, cellScan.entityOcclusion());
        }
        entityRenderer.applyRecursive(observer, new ProjectedEntityRenderer.RecursiveRender(portal, frustum, depthBlocks,
            viewProvider.usesRegionSnapshots(), destination::liveView, cellScan.entityOcclusion()));
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
        AcousticsBridge<Player> bridge = FidelitySubsystem.acoustics();
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
                center.getX(), center.getY(), center.getZ(), profile, AcousticsBridge.Environment.valueOf(destWorld.getEnvironment().name()), destWorld.hasStorm(), now);
            return;
        }
        if (destination.destAnchor instanceof RemotePortal remote && portal.getTunnel() instanceof UniversalTunnel universal) {
            bridge.noteRemoteDestination(portal.getId(), universal.getServerName(), remote.getId(),
                destination.originX, destination.originY, destination.originZ,
                center.getX(), center.getY(), center.getZ(), profile, now);
        }
    }

    private void driveAtmosphere(World submitWorld, AtmosphereMode mode, boolean claimsChanged) {
        if (portal.getId() == null) {
            return;
        }
        if (FidelitySettings.biomeTint && mode.tintsBiomes()) {
            Long2IntOpenHashMap overrides = atmosphere.update(
                new AtmosphereChannel.Scan<>(cellScan.claims(), destination.destView, claimsChanged), FidelitySettings.snapshot());
            if (overrides != null) {
                claimArbiter.submitBiomes(observer, portal.getId(), submitWorld, overrides);
            }
        } else if (atmosphere.disable()) {
            claimArbiter.submitBiomes(observer, portal.getId(), submitWorld, new Long2IntOpenHashMap());
        }
        BukkitOpticsScheduler scheduler = BukkitOpticsScheduler.active();
        if (!FidelitySettings.weather || !mode.relaysWeather() || scheduler == null) {
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
        weather.relay(scheduler, claimArbiter.output(), observer, new ProjectorWeather.Conditions(storm, thunder, biome), cellScan.claims());
    }

    /**
     * The plate this portal shares with every observer standing on the same side of it. Only
     * portal-scoped inputs may reach the revision: the coarsening {@link ProjectorViewFrustum#fit}
     * applies for one observer's eye and client view distance stays on that observer's scan, or two
     * observers of one portal would invalidate each other's plate every frame.
     */
    private ViewPlate<BlockData> acquirePlate(Location eye, boolean buriedCellCulling, long destinationRevision,
                                              RtpProjectionTarget rtpTarget) {
        if (!ProjectorPlates.enabled(plateCache, portal, rtpTarget)) {
            return null;
        }
        ProjectorPlates.Target target = ProjectorPlates.target(portal, destination, ProjectorPlates.frontSide(portal, eye),
            buriedCellCulling, rtpTarget, fidelityExtension());
        return ProjectorPlates.acquire(plateCache, viewProvider, portal, destination, sampler.air(), target, destinationRevision, false);
    }

    private static ResampleSchedule.Cadence resampleCadence() {
        return new ResampleSchedule.Cadence(Settings.PROJECTION_REFRESH_INTERVAL_TICKS,
            Settings.PROJECTION_STABLE_CELL_RESAMPLE_INTERVAL_TICKS, Settings.LIGHTING_REFRESH_INTERVAL_TICKS,
            Settings.ENTITY_UPDATE_INTERVAL_TICKS);
    }

    /** The portal's own level of detail, before any per-observer coarsening. */
    private static LodPolicy portalLod(FidelityPortalExtension fidelity) {
        return ProjectorPlates.portalLod(fidelity);
    }

    private long presentationRevision(Location eye, RtpProjectionTarget rtpTarget, boolean buriedCellCulling,
                                      LodPolicy lod, boolean blockEntities) {
        Frame localFrame = portal.getFrame();
        Frame remoteFrame = rtpTarget != null ? rtpTarget.frame()
            : destination.mirrorMode ? localFrame.flipNormal() : destination.destAnchor.getFrame();
        double localOriginX = portal.getOrigin().getX();
        double localOriginY = portal.getOrigin().getY();
        double localOriginZ = portal.getOrigin().getZ();
        double remoteOriginX = destination.mirrorMode ? localOriginX : destination.originX;
        double remoteOriginY = destination.mirrorMode ? localOriginY : destination.originY;
        double remoteOriginZ = destination.mirrorMode ? localOriginZ : destination.originZ;
        Face facing = localFrame.getNormal();
        boolean eyeFrontSide = ((eye.getX() - localOriginX) * facing.x()
            + (eye.getY() - localOriginY) * facing.y()
            + (eye.getZ() - localOriginZ) * facing.z()) >= 0.0D;
        long revision = ProjectorPassRevision.transform(localFrame, remoteFrame, localOriginX, localOriginY, localOriginZ,
            remoteOriginX, remoteOriginY, remoteOriginZ, portal.getNetworkViewDepth(), portal.getNetworkViewLateralPad(),
            Settings.PROJECTION_APERTURE_PADDING_BLOCKS, buriedCellCulling, lod, blockEntities);
        revision = ProjectorPassRevision.mix(revision, destination.mirrorRotationQuarterTurns);
        return ProjectorPassRevision.mix(revision, eyeFrontSide ? 1L : 0L);
    }

    FidelityPortalExtension fidelityExtension() {
        return ProjectorPlates.fidelity(portal);
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




    static Frame viewFrame(Frame frame, boolean frontSide) {
        return frame.view(frontSide);
    }





    private void logDiagnostics(boolean changed, int enter, int exit, int kept) {
        long passCount = schedule.passCount();
        boolean due = passCount <= 3L ? changed : passCount - lastDiagLogCall >= DIAG_LOG_INTERVAL_PASSES;
        if (!due) {
            return;
        }
        lastDiagLogCall = passCount;
        if (Settings.DEBUG) {
            Wormholes.v("[Projector] portal=" + portal.getName() + " observer=" + observer.getName()
                + " diff: enter=" + enter + " exit=" + exit + " kept=" + kept
                + " " + getDiagnostics() + " resampleReason=" + resampleReasons.mask()
                + " resamples=" + resampleReasons.describe() + " call#" + passCount);
        }
        resampleReasons.reset();
    }

    private int reuseBlockers(Location eye, boolean projectionInvalidated, boolean stableResample, boolean localDirty,
                              boolean renderModeChanged) {
        int reasons = 0;
        if (projectionInvalidated) {
            reasons |= ProjectorResampleReasons.INVALIDATED;
        }
        if (cellScan.hasUnresolvedOcclusion()) {
            reasons |= ProjectorResampleReasons.UNRESOLVED_OCCLUSION;
        }
        if (stableResample) {
            reasons |= ProjectorResampleReasons.STABLE_CADENCE;
        }
        if (localDirty) {
            reasons |= ProjectorResampleReasons.LOCAL_DIRTY;
        }
        if (schedule.isRemoteResamplePending()) {
            reasons |= ProjectorResampleReasons.REMOTE_PENDING;
        }
        if (claimArbiter.hasPendingLighting(observer) && schedule.lightingUpdatePass(firstProjectionDone)) {
            reasons |= ProjectorResampleReasons.LIGHTING;
        }
        if (!firstProjectionDone || !cellScan.hasProjection() || initialFullSendPassesRemaining > 0) {
            reasons |= ProjectorResampleReasons.FULL_SEND;
        }
        if (renderModeChanged || dissolve.isActive()) {
            reasons |= ProjectorResampleReasons.PRESENTATION;
        }
        if (!hasCameraSnapshot || cameraLeftReuseWindow(eye)) {
            reasons |= ProjectorResampleReasons.CAMERA;
        }
        return reasons;
    }

    private boolean cameraLeftReuseWindow(Location eye) {
        double dx = eye.getX() - lastEyeX;
        double dy = eye.getY() - lastEyeY;
        double dz = eye.getZ() - lastEyeZ;
        if ((dx * dx) + (dy * dy) + (dz * dz) >= GazeScheduler.REUSE_EYE_EPSILON_SQUARED) {
            return true;
        }
        Face normal = portal.getFrame().getNormal();
        double originNormal = axisValueOf(portal.getOrigin().getX(), portal.getOrigin().getY(), portal.getOrigin().getZ(), normal);
        return (axisValueOf(eye.getX(), eye.getY(), eye.getZ(), normal) >= originNormal)
            != (axisValueOf(lastEyeX, lastEyeY, lastEyeZ, normal) >= originNormal);
    }

    private boolean exposeLosingClaims() {
        boolean resync = cellScan.losingClaimsUnsynced();
        claimArbiter.drainLosingTransitions(observer, portal.getId(), displacedClaimKeys, restoredClaimKeys, resync);
        boolean exposed = cellScan.exposeLosingClaims(displacedClaimKeys, restoredClaimKeys, resync);
        displacedClaimKeys.clear();
        restoredClaimKeys.clear();
        return exposed;
    }

    private boolean canReuseProjection(Location eye, boolean stableResample, boolean localDirty) {
        if (reuseInvalidated || lastCommittedGeneration != invalidationGeneration.get()) {
            return false;
        }
        if (cellScan.hasUnresolvedOcclusion()) {
            return false;
        }
        boolean lightingBlocked = claimArbiter.hasPendingLighting(observer) && schedule.lightingUpdatePass(firstProjectionDone);
        Face normal = portal.getFrame().getNormal();
        double originNormal = axisValueOf(portal.getOrigin().getX(), portal.getOrigin().getY(), portal.getOrigin().getZ(), normal);
        boolean sideFlipped = hasCameraSnapshot
            && (axisValueOf(eye.getX(), eye.getY(), eye.getZ(), normal) >= originNormal)
                != (axisValueOf(lastEyeX, lastEyeY, lastEyeZ, normal) >= originNormal);
        return canReuseProjection(firstProjectionDone, cellScan.hasProjection(), hasCameraSnapshot,
            initialFullSendPassesRemaining, stableResample, schedule.isRemoteResamplePending(), lightingBlocked, sideFlipped,
            localDirty,
            eye.getX(), eye.getY(), eye.getZ(), lastEyeX, lastEyeY, lastEyeZ);
    }

    private static double axisValueOf(double x, double y, double z, Face normal) {
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
        return movedSquared < GazeScheduler.REUSE_EYE_EPSILON_SQUARED;
    }

    static boolean requiresViewCellResample(ProjectionRenderMode renderMode,
                                               boolean hasCameraSnapshot,
                                               double eyeX,
                                               double eyeY,
                                               double eyeZ,
                                               double lastEyeX,
                                               double lastEyeY,
                                               double lastEyeZ) {
        if (renderMode == null || !renderMode.scanMode().observerOcclusion() || !hasCameraSnapshot) {
            return false;
        }
        double dx = eyeX - lastEyeX;
        double dy = eyeY - lastEyeY;
        double dz = eyeZ - lastEyeZ;
        return (dx * dx) + (dy * dy) + (dz * dz) >= GazeScheduler.REUSE_EYE_EPSILON_SQUARED;
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
        releaseEndSurface();
        cancelPendingProjection();
        cellScan.dropHolds();
        schedule.invalidateDestination();
        sampler.resetRecursiveSamplesCached();
        sampleMemo.discard();
        sampler.clearRecursivePortals();
        entityRenderer.close(observer);
    }

    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;

        if (releaseClaims()) {
            entityRenderer.close(observer);
            restoreLocalBlockEntities();
        } else {
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
            () -> blockEntityLayer.flush(observer, Integer.MAX_VALUE, claimArbiter.output()), 1L);
        if (!scheduled) {
            blockEntityLayer.flush(observer, Integer.MAX_VALUE, claimArbiter.output());
        }
    }

    void updateEndSurface(ProjectionWorldView localView, World localWorld, double priorityDistance) {
        if (endSurfaceOwner == null) {
            return;
        }
        endSurfaceClaims.clear();
        for (Vec3d cell : portal.getStructure().geometry().getBlockPositions()) {
            int x = cell.getBlockX();
            int y = cell.getBlockY();
            int z = cell.getBlockZ();
            if (!localView.isChunkReady(x, z) || localView.material(x, y, z) != Material.END_PORTAL) {
                continue;
            }
            if (endSurfaceAir == null) {
                endSurfaceAir = new ProjectedBlockClaim<>(Bukkit.createBlockData(Material.AIR), null,
                    ProjectedBlockClaim.NO_REMOTE_KEY, true);
            }
            endSurfaceClaims.put(CellKeys.pack(x, y, z), endSurfaceAir);
        }
        endSurfaceActive |= !endSurfaceClaims.isEmpty();
        claimArbiter.submit(observer, endSurfaceOwner, localWorld, endSurfaceClaims, priorityDistance, false);
        endSurfaceActive = !endSurfaceClaims.isEmpty();
    }

    private void releaseEndSurface() {
        if (endSurfaceOwner == null || !endSurfaceActive) {
            return;
        }
        World world = claimWorld;
        if (observer.isOnline() && world != null && world.equals(observer.getWorld())) {
            claimArbiter.release(observer, endSurfaceOwner, world, false);
        }
        endSurfaceClaims.clear();
        endSurfaceActive = false;
        reuseInvalidated = true;
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

        releaseEndSurface();
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
        entityRenderer.discard(observer);
        blockEntityLayer.clear();
    }

    public void requestDiscard() {
        discardRequested = true;
    }

    private static String formatBox(Box box) {
        if (box == null) {
            return "null";
        }
        return "[" + box.getXa() + "," + box.getYa() + "," + box.getZa()
            + " -> " + box.getXb() + "," + box.getYb() + "," + box.getZb() + "]";
    }

    private record PendingProjection(Location eye, ViewVolume frustum, double depthBlocks, boolean coarse,
                                     FidelityPortalExtension fidelity, ProjectionRenderMode renderMode,
                                     boolean forceFullSend, long presentationRevision, long destinationRevision,
                                     ProjectionWorldView localView, ProjectionWorldView destinationView,
                                     IPortal destinationAnchor, long contextRevision, long invalidationGeneration) {
    }

    public record RtpProjectionTarget(World world, double originX, double originY, double originZ,
                                      Frame frame, long routeRevision) {
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
            Face normal = direction(target.forward(), "forward").reverse();
            Face right = direction(target.right(), "right");
            Face up = direction(target.up(), "up");
            Frame frame = new Frame(normal, right, up);
            RtpProjectionView.Point3 safeFeet = target.safeFeet();
            return new RtpProjectionTarget(world, safeFeet.x(), safeFeet.y(), safeFeet.z(), frame,
                    requiredReadyData.routeRevision());
        }

        public boolean requiresDestinationInvalidation(RtpProjectionTarget previous) {
            return previous == null || routeRevision != previous.routeRevision;
        }

        public long plateIdentity() {
            return RtpProjectionGeometry.plateIdentity(world.getUID(), originX, originY, originZ, frame, routeRevision);
        }

        private static Face direction(Vec3d vector, String name) {
            Vec3d requiredVector = Objects.requireNonNull(vector, name);
            double lengthSquared = requiredVector.x() * requiredVector.x()
                    + requiredVector.y() * requiredVector.y()
                    + requiredVector.z() * requiredVector.z();
            if (lengthSquared <= 1.0E-12D) {
                throw new IllegalArgumentException(name + " must not be zero");
            }
            return Face.closest(requiredVector.x(), requiredVector.y(), requiredVector.z());
        }
    }
}
