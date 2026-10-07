package art.arcane.optics.scan;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Supplier;
import art.arcane.optics.internal.occlusion.LocalOccupancy;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.aperture.Endpoint;
import art.arcane.optics.aperture.CellAperture;
import art.arcane.optics.view.ContentView;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.volume.LodPolicy;
import art.arcane.optics.plate.PlateCell;
import art.arcane.optics.plate.ViewPlate;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.claim.BlockClaim;
import art.arcane.optics.claim.Blackout;
import art.arcane.optics.claim.ClaimSet;
import art.arcane.optics.frame.ViewWindow;
import art.arcane.optics.internal.occlusion.BlackoutBoundary;
import art.arcane.optics.internal.occlusion.HoldProof;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.occlusion.EntityOcclusion;
import art.arcane.optics.occlusion.ViewOcclusion;
import art.arcane.optics.recursion.RecursiveEndpoints;
import art.arcane.optics.volume.FrustumRow;
import art.arcane.optics.volume.PlaneWindow;
import art.arcane.optics.volume.ViewVolume;
import art.arcane.optics.volume.ApertureSlab;

public final class CellScan<B, M, W, P extends Endpoint, V extends ContentView<B, M>> {
    private static final int FINISH_SEAL = 0;
    private static final int FINISH_UNRESOLVED_TARGETS = 1;
    private static final int FINISH_OBSERVER_TARGETS = 2;
    private static final int FINISH_DEADLINE_STRIDE = 128;

    private final P portal;
    private final CellAperture aperture;
    private final Supplier<ScanSettings> settings;
    private final Sampler<B, M, W, P, V> sampler;
    private final SampleMemo<B, M, V> memo;
    private final Blackout<B> blackout;
    private final ViewOcclusion<B> viewOcclusion;
    private EntityOcclusion<B, V> entityOcclusion;
    private EntityOcclusion<B, V> projectedEntityOcclusion;
    private final BlackoutBoundary blackoutBoundary;
    private OpticTransform cellTransform;
    private final double[] scratchRemotePoint;
    private final double[] scratchRemoteEye;
    private final double[] scratchRemoteBounds;
    private final double[] scratchMaskRowStart;
    private final double[] scratchMaskRowNext;
    private final double[] scratchMaskRange;
    private final ArrayList<RecursiveEndpoints<W, P>.Candidate> maskCandidates;
    private int[] maskRowCandidates;
    private int[] maskRowLows;
    private int[] maskRowHighs;
    private final int[] scratchAxisMin;
    private final int[] scratchAxisMax;
    private final double[] scratchAxisOrigin;
    private final double[] scratchSlabWindowBounds;
    private final int[] scratchCellCoords;
    private final Long2ByteOpenHashMap localChunkReadiness;
    private final EmptyCellRuns emptyCells;
    private final FrustumRow frustumRow;
    private final LongOpenHashSet blackoutGeometry;
    private final Long2LongOpenHashMap blackoutRemoteKeys;
    private LongOpenHashSet occlusionGeometry;
    private LongOpenHashSet projectedOcclusionGeometry;
    private final LongArrayList observerTargetCells;
    private final LongArrayList observerTargetRemoteKeys;
    private final LongArrayList unresolvedTargetCells;
    private final LongArrayList unresolvedTargetRemoteKeys;
    private LongOpenHashSet projectedUnresolvedOcclusion;
    private LongOpenHashSet nextUnresolvedOcclusion;
    private int resumeTraced;
    private int resumeResolved;
    private int resumeUnresolvedBefore;
    private Long2ObjectOpenHashMap<BlockClaim<B, V>> projected;
    private Long2ObjectOpenHashMap<BlockClaim<B, V>> nextProjected;
    private final LongOpenHashSet changedClaimKeys;
    private final LongOpenHashSet removedClaimKeys;
    private Long2ObjectMap<BlockClaim<B, V>> deltaBaseline;
    private int retainedClaimCount;
    private int unfilteredClaimCount;
    private Long2ObjectOpenHashMap<BlockEntitySample> projectedBlockEntities;
    private Long2ObjectOpenHashMap<BlockEntitySample> nextBlockEntities;
    private Frame projectionLocalFrame;
    private Frame projectionRemoteFrame;
    private double projectionEyeDot;
    private int enterCount;
    private int exitCount;
    private int keptCount;
    private int planeRejected;
    private int windowRejected;
    private int frustumRejected;
    private int frustumMaskedRows;
    private int frustumScalarRows;
    private int occlusionRejected;
    private int maskedCells;
    private int plateHits;
    private V scannedLocalView;
    private V scannedDestinationView;
    private ViewVolume scannedFrustum;
    private long scannedLocalRevision;
    private long scannedDestinationRevision;
    private double scannedEyeX;
    private double scannedEyeY;
    private double scannedEyeZ;
    private double scannedRemoteEyeX;
    private double scannedRemoteEyeY;
    private double scannedRemoteEyeZ;
    private double scannedRevealMargin;
    private boolean scannedBlackout;
    private boolean completeGeometry;
    private boolean scanCommitted;
    private int emptyCellSkips;
    private CellMapping scannedMapping;
    private ScanPass pending;
    private boolean preparedResult;
    private boolean reuseCommittedEntityOcclusion;
    private int blackoutClaims;
    private Frame committedLocalFrame;
    private Frame committedRemoteFrame;
    private double committedEyeDot;
    private Long2LongOpenHashMap heldSince;
    private Long2LongOpenHashMap nextHeldSince;
    private Long2ObjectOpenHashMap<long[]> heldBlockers;
    private Long2ObjectOpenHashMap<long[]> nextHeldBlockers;
    private final LongOpenHashSet losingClaimKeys;
    private final LongOpenHashSet losingBlockers;
    private boolean losingClaimsUnsynced;
    private boolean holdClaims;
    private boolean holdConeClaims;
    private boolean coneHoldsRevoked;
    private boolean holdsExposed;
    private boolean dropHoldsRequested;
    private boolean passRevokesConeHolds;
    private boolean passExposesHolds;
    private boolean passDropsHolds;
    private int maxHeldClaims;
    private long holdGeneration;
    private boolean removedClaimsResolved;
    private int resolvedClaimCount;
    private int hiddenHolds;
    private int coneHolds;
    private int heldEvictions;
    private RemoteFootprint remoteFootprint;
    private RemoteFootprint nextRemoteFootprint;
    private boolean restartRemoteFootprint;

    public CellScan(Context<B, M, W, P, V> context) {
        this.portal = context.portal();
        this.aperture = context.aperture();
        this.settings = context.settings();
        this.sampler = context.sampler();
        this.memo = context.memo();
        this.blackout = context.blackout();
        this.viewOcclusion = new ViewOcclusion<B>(memo.blocks());
        this.entityOcclusion = new EntityOcclusion<B, V>(new ViewOcclusion<B>(memo.blocks(), EntityOcclusion.MAX_VOXEL_STEPS_PER_BATCH));
        this.projectedEntityOcclusion = new EntityOcclusion<B, V>(new ViewOcclusion<B>(memo.blocks(), EntityOcclusion.MAX_VOXEL_STEPS_PER_BATCH));
        this.blackoutBoundary = new BlackoutBoundary();
        this.cellTransform = OpticTransform.IDENTITY;
        this.scratchRemotePoint = new double[3];
        this.scratchRemoteEye = new double[3];
        this.scratchRemoteBounds = new double[6];
        this.scratchMaskRowStart = new double[3];
        this.scratchMaskRowNext = new double[3];
        this.scratchMaskRange = new double[2];
        this.maskCandidates = new ArrayList<RecursiveEndpoints<W, P>.Candidate>(4);
        this.maskRowCandidates = new int[4];
        this.maskRowLows = new int[4];
        this.maskRowHighs = new int[4];
        this.scratchAxisMin = new int[3];
        this.scratchAxisMax = new int[3];
        this.scratchAxisOrigin = new double[3];
        this.scratchSlabWindowBounds = new double[4];
        this.scratchCellCoords = new int[3];
        this.localChunkReadiness = new Long2ByteOpenHashMap(16);
        this.emptyCells = new EmptyCellRuns();
        this.frustumRow = new FrustumRow();
        this.blackoutGeometry = new LongOpenHashSet(256);
        this.blackoutRemoteKeys = new Long2LongOpenHashMap(256);
        this.occlusionGeometry = new LongOpenHashSet(256);
        this.projectedOcclusionGeometry = new LongOpenHashSet(256);
        this.observerTargetCells = new LongArrayList(256);
        this.observerTargetRemoteKeys = new LongArrayList(256);
        this.unresolvedTargetCells = new LongArrayList(256);
        this.unresolvedTargetRemoteKeys = new LongArrayList(256);
        this.projectedUnresolvedOcclusion = new LongOpenHashSet(256);
        this.nextUnresolvedOcclusion = new LongOpenHashSet(256);
        this.projected = new Long2ObjectOpenHashMap<BlockClaim<B, V>>(256);
        this.nextProjected = new Long2ObjectOpenHashMap<BlockClaim<B, V>>(256);
        this.changedClaimKeys = new LongOpenHashSet(64);
        this.removedClaimKeys = new LongOpenHashSet(64);
        this.projectedBlockEntities = new Long2ObjectOpenHashMap<BlockEntitySample>(16);
        this.nextBlockEntities = new Long2ObjectOpenHashMap<BlockEntitySample>(16);
        this.heldSince = new Long2LongOpenHashMap(64);
        this.nextHeldSince = new Long2LongOpenHashMap(64);
        this.heldSince.defaultReturnValue(Long.MIN_VALUE);
        this.nextHeldSince.defaultReturnValue(Long.MIN_VALUE);
        this.heldBlockers = new Long2ObjectOpenHashMap<long[]>(64);
        this.nextHeldBlockers = new Long2ObjectOpenHashMap<long[]>(64);
        this.losingClaimKeys = new LongOpenHashSet(16);
        this.losingBlockers = new LongOpenHashSet(16);
        this.losingClaimsUnsynced = true;
        this.remoteFootprint = new RemoteFootprint();
        this.nextRemoteFootprint = new RemoteFootprint();
    }

    public Long2ObjectOpenHashMap<BlockClaim<B, V>> claims() {
        return preparedResult ? nextProjected : projected;
    }

    public ClaimSet.ClaimDelta<BlockClaim<B, V>> claimDelta() {
        if (!preparedResult) {
            throw new IllegalStateException("Projection scan is not complete");
        }
        if (removedClaimsResolved && nextProjected.size() == resolvedClaimCount) {
            return new ClaimSet.ClaimDelta<BlockClaim<B, V>>(deltaBaseline, nextProjected, changedClaimKeys, removedClaimKeys);
        }
        removedClaimKeys.clear();
        if (deltaBaseline != null
            && (retainedClaimCount != deltaBaseline.size() || nextProjected.size() != unfilteredClaimCount)) {
            LongIterator previous = deltaBaseline.keySet().iterator();
            while (previous.hasNext()) {
                long key = previous.nextLong();
                if (!nextProjected.containsKey(key)) {
                    removedClaimKeys.add(key);
                }
            }
        }
        return new ClaimSet.ClaimDelta<BlockClaim<B, V>>(deltaBaseline, nextProjected, changedClaimKeys, removedClaimKeys);
    }

    public Long2ObjectOpenHashMap<BlockEntitySample> blockEntities() {
        return preparedResult ? nextBlockEntities : projectedBlockEntities;
    }

    public boolean hasProjection() {
        return !projected.isEmpty();
    }

    /** Shell cells the last completed scan sealed with the blackout block. */
    public int blackoutClaims() {
        return blackoutClaims;
    }

    public EntityOcclusion<B, V> entityOcclusion() {
        return preparedResult && !reuseCommittedEntityOcclusion ? entityOcclusion : projectedEntityOcclusion;
    }

    public Frame localFrame() {
        return preparedResult ? projectionLocalFrame : committedLocalFrame;
    }

    public Frame remoteFrame() {
        return preparedResult ? projectionRemoteFrame : committedRemoteFrame;
    }

    public double eyeDot() {
        return preparedResult ? projectionEyeDot : committedEyeDot;
    }

    public int enterCount() {
        return enterCount;
    }

    public int exitCount() {
        return exitCount;
    }

    public int keptCount() {
        return keptCount;
    }

    public int planeRejected() {
        return planeRejected;
    }

    public int windowRejected() {
        return windowRejected;
    }

    public int frustumRejected() {
        return frustumRejected;
    }

    public int frustumMaskedRows() {
        return frustumMaskedRows;
    }

    public int frustumScalarRows() {
        return frustumScalarRows;
    }

    public int occlusionRejected() {
        return occlusionRejected;
    }

    public int occlusionVoxelSteps() {
        return viewOcclusion.voxelSteps();
    }

    public boolean occlusionBudgetExhausted() {
        return viewOcclusion.budgetExhausted();
    }

    public int occlusionProofHits() {
        return viewOcclusion.hiddenProofHits();
    }

    public int occlusionProofRevalidations() {
        return viewOcclusion.hiddenProofRevalidations();
    }

    public int occlusionProofInvalidations() {
        return viewOcclusion.hiddenProofInvalidations();
    }

    public int adjacentOcclusionHits() {
        return viewOcclusion.adjacentOcclusionHits();
    }

    public int unresolvedOcclusionCells() {
        return nextUnresolvedOcclusion.size();
    }

    public int resumeTraced() {
        return resumeTraced;
    }

    public int resumeResolved() {
        return resumeResolved;
    }

    public int resumeUnresolvedBefore() {
        return resumeUnresolvedBefore;
    }

    public int occlusionVerdictHits() {
        return viewOcclusion.verdictHits();
    }

    public boolean hasUnresolvedOcclusion() {
        return !projectedUnresolvedOcclusion.isEmpty();
    }

    public int maskedCells() {
        return maskedCells;
    }

    public int plateHits() {
        return plateHits;
    }

    public int emptyCellSkips() {
        return emptyCellSkips;
    }

    public int heldClaims() {
        return preparedResult ? nextHeldSince.size() : heldSince.size();
    }

    public int hiddenHolds() {
        return hiddenHolds;
    }

    public int coneHolds() {
        return coneHolds;
    }

    public int heldEvictions() {
        return heldEvictions;
    }

    public int losingClaims() {
        return losingClaimKeys.size();
    }

    public boolean losingClaimsUnsynced() {
        return losingClaimsUnsynced;
    }

    public RemoteFootprint remoteFootprint() {
        return remoteFootprint;
    }

    public void restartRemoteFootprint() {
        restartRemoteFootprint = true;
    }

    public void revokeConeHolds() {
        coneHoldsRevoked = true;
    }

    public boolean exposeLosingClaims(LongSet displacedKeys, LongSet restoredKeys, boolean resync) {
        if (resync) {
            losingClaimKeys.clear();
            losingClaimsUnsynced = false;
        } else if (!restoredKeys.isEmpty()) {
            losingClaimKeys.removeAll(restoredKeys);
        }
        LongIterator displaced = displacedKeys.iterator();
        while (displaced.hasNext()) {
            long key = displaced.nextLong();
            losingClaimKeys.add(key);
            BlockClaim<B, V> claim = projected.get(key);
            if (claim == null || claim.getLightRemoteKey() == BlockClaim.NO_REMOTE_KEY) {
                continue;
            }
            long remoteKey = claim.getLightRemoteKey();
            losingBlockers.add(remoteKey);
            if (!heldBlockers.isEmpty() && projectedOcclusionGeometry.contains(remoteKey)) {
                holdsExposed = true;
            }
        }
        return holdsExposed;
    }

    public boolean holdsExposed() {
        return holdsExposed;
    }

    public void dropHolds() {
        dropHoldsRequested = true;
    }

    public void invalidateContent() {
        emptyCells.clear();
    }

    public void clear() {
        pending = null;
        preparedResult = false;
        reuseCommittedEntityOcclusion = false;
        projectedOcclusionGeometry.clear();
        projectedEntityOcclusion.disable();
        blackoutClaims = 0;
        committedLocalFrame = null;
        committedRemoteFrame = null;
        committedEyeDot = 0.0D;
        emptyCells.clear();
        changedClaimKeys.clear();
        removedClaimKeys.clear();
        deltaBaseline = null;
        completeGeometry = false;
        scanCommitted = false;
        scannedLocalView = null;
        scannedDestinationView = null;
        scannedFrustum = null;
        localChunkReadiness.clear();
        projected.clear();
        nextProjected.clear();
        projectedBlockEntities.clear();
        nextBlockEntities.clear();
        blackoutGeometry.clear();
        blackoutBoundary.clear();
        blackoutRemoteKeys.clear();
        occlusionGeometry.clear();
        observerTargetCells.clear();
        observerTargetRemoteKeys.clear();
        unresolvedTargetCells.clear();
        unresolvedTargetRemoteKeys.clear();
        projectedUnresolvedOcclusion.clear();
        nextUnresolvedOcclusion.clear();
        heldSince.clear();
        nextHeldSince.clear();
        heldBlockers.clear();
        nextHeldBlockers.clear();
        losingClaimKeys.clear();
        losingBlockers.clear();
        losingClaimsUnsynced = true;
        holdClaims = false;
        holdConeClaims = false;
        coneHoldsRevoked = false;
        holdsExposed = false;
        dropHoldsRequested = false;
        clearPassHoldRequests();
        removedClaimsResolved = false;
        remoteFootprint.clear();
        nextRemoteFootprint.clear();
        restartRemoteFootprint = false;
        entityOcclusion.disable();
    }

    public void commit() {
        if (!preparedResult) {
            throw new IllegalStateException("Projection scan is not complete");
        }
        if (!reuseCommittedEntityOcclusion) {
            LongOpenHashSet occlusionSwap = projectedOcclusionGeometry;
            projectedOcclusionGeometry = occlusionGeometry;
            occlusionGeometry = occlusionSwap;
            EntityOcclusion<B, V> entityOcclusionSwap = projectedEntityOcclusion;
            projectedEntityOcclusion = entityOcclusion;
            entityOcclusion = entityOcclusionSwap;
        }
        committedLocalFrame = projectionLocalFrame;
        committedRemoteFrame = projectionRemoteFrame;
        committedEyeDot = projectionEyeDot;
        if (pending != null) {
            commitRemoteFootprint(pending.freshRemoteFootprint);
        }
        pending = null;
        preparedResult = false;
        reuseCommittedEntityOcclusion = false;
        Long2ObjectOpenHashMap<BlockClaim<B, V>> swap = projected;
        projected = nextProjected;
        nextProjected = swap;
        Long2ObjectOpenHashMap<BlockEntitySample> blockEntitySwap = projectedBlockEntities;
        projectedBlockEntities = nextBlockEntities;
        nextBlockEntities = blockEntitySwap;
        LongOpenHashSet unresolvedSwap = projectedUnresolvedOcclusion;
        projectedUnresolvedOcclusion = nextUnresolvedOcclusion;
        nextUnresolvedOcclusion = unresolvedSwap;
        Long2LongOpenHashMap heldSwap = heldSince;
        heldSince = nextHeldSince;
        nextHeldSince = heldSwap;
        nextHeldSince.clear();
        Long2ObjectOpenHashMap<long[]> blockersSwap = heldBlockers;
        heldBlockers = nextHeldBlockers;
        nextHeldBlockers = blockersSwap;
        nextHeldBlockers.clear();
        holdClaims = false;
        holdConeClaims = false;
        clearPassHoldRequests();
        removedClaimsResolved = false;
        scanCommitted = true;
    }

    public boolean canResumeOcclusion(ScanDestination<P, V> destination, Vec3d eye, ViewVolume frustum) {
        return scanCommitted && completeGeometry && hasUnresolvedOcclusion()
            && !dropHoldsRequested && !holdsExposed
            && frustum == scannedFrustum
            && destination.localView() == scannedLocalView && destination.destView() == scannedDestinationView
            && scannedLocalRevision == destination.localView().getRevision()
            && scannedDestinationRevision == destination.destView().getRevision()
            && eye.getX() == scannedEyeX && eye.getY() == scannedEyeY && eye.getZ() == scannedEyeZ
            && scannedRevealMargin == settings.get().revealMarginDegrees()
            && scannedBlackout == blackout.isEnabled();
    }

    public void invalidateOcclusionContinuation() {
        completeGeometry = false;
    }

    public void resumeOcclusion() {
        preparedResult = true;
        reuseCommittedEntityOcclusion = true;
        holdClaims = beginHolding(scanCommitted);
        deltaBaseline = scanCommitted ? projected : null;
        retainedClaimCount = projected.size();
        unfilteredClaimCount = projected.size();
        changedClaimKeys.clear();
        removedClaimKeys.clear();
        scanCommitted = false;
        nextProjected.clear();
        nextProjected.putAll(projected);
        nextBlockEntities.clear();
        nextBlockEntities.putAll(projectedBlockEntities);
        nextUnresolvedOcclusion.clear();
        nextHeldSince.clear();
        nextHeldSince.putAll(heldSince);
        nextHeldBlockers.clear();
        nextHeldBlockers.putAll(heldBlockers);
        enterCount = 0;
        exitCount = 0;
        keptCount = 0;
        planeRejected = 0;
        windowRejected = 0;
        frustumRejected = 0;
        frustumMaskedRows = 0;
        frustumScalarRows = 0;
        occlusionRejected = 0;
        maskedCells = 0;
        plateHits = 0;
        viewOcclusion.restartTraceBudget();
        resumeUnresolvedBefore = projectedUnresolvedOcclusion.size();
        resumeTraced = 0;
        filterUnresolvedTargets(unresolvedTargetCells, unresolvedTargetRemoteKeys);
        filterUnresolvedTargets(observerTargetCells, observerTargetRemoteKeys);
        resumeResolved = resumeTraced - nextUnresolvedOcclusion.size();
        evictOverflowHeldClaims();
        projectedEntityOcclusion.updateEye(scannedRemoteEyeX, scannedRemoteEyeY, scannedRemoteEyeZ);
        if (settings.get().debug()) {
            recountProjectionChanges(false);
        }
    }

    public void run(ScanDestination<P, V> destination,
             Frame targetFrame,
             Vec3d eye,
             ViewVolume frustum,
             double depthBlocks,
             boolean forceStableCellResample,
             boolean forceFullSend,
             boolean refreshObserverVisibility,
             ScanMode mode,
             ViewPlate<B> plate,
             boolean blockEntities,
             LodPolicy lod) {
        begin(destination, targetFrame, eye, frustum, depthBlocks, forceStableCellResample, forceFullSend,
            refreshObserverVisibility, mode, plate, blockEntities, lod);
        while (!advance(Long.MAX_VALUE)) {
        }
    }

    public void begin(ScanDestination<P, V> destination,
             Frame targetFrame,
             Vec3d eye,
             ViewVolume frustum,
             double depthBlocks,
             boolean forceStableCellResample,
             boolean forceFullSend,
             boolean refreshObserverVisibility,
             ScanMode mode,
             ViewPlate<B> plate,
             boolean blockEntities,
             LodPolicy lod) {
        if (pending != null) {
            cancelPending();
        }
        preparedResult = false;
        reuseCommittedEntityOcclusion = false;
        pending = new ScanPass(new ScanRequest<B, P, V>(destination, targetFrame, eye, frustum, depthBlocks,
            forceStableCellResample, forceFullSend, refreshObserverVisibility, mode, plate, blockEntities, lod));
    }

    public boolean advance(long deadlineNanos) {
        ScanPass pass = pending;
        if (pass == null) {
            return preparedResult;
        }
        if (pass.ready) {
            return true;
        }
        if (!pass.geometryComplete) {
            if (!pass.advanceGeometry(deadlineNanos)) {
                return false;
            }
            pass.geometryComplete = true;
            if (!finishesInSlot(deadlineNanos)) {
                return false;
            }
        }
        if (!pass.finishGeometry(deadlineNanos)) {
            return false;
        }
        pass.ready = true;
        preparedResult = true;
        return true;
    }

    public boolean hasPending() {
        return pending != null;
    }

    public void cancelPending() {
        if (pending != null) {
            remoteFootprint.addAll(nextRemoteFootprint);
            nextRemoteFootprint.clear();
        }
        coneHoldsRevoked |= passRevokesConeHolds;
        holdsExposed |= passExposesHolds;
        dropHoldsRequested |= passDropsHolds;
        clearPassHoldRequests();
        pending = null;
        preparedResult = false;
        reuseCommittedEntityOcclusion = false;
        scanCommitted = false;
        completeGeometry = false;
        deltaBaseline = null;
        emptyCells.clear();
        changedClaimKeys.clear();
        removedClaimKeys.clear();
        nextProjected.clear();
        nextBlockEntities.clear();
        nextHeldSince.clear();
        nextHeldBlockers.clear();
        holdClaims = false;
        holdConeClaims = false;
        removedClaimsResolved = false;
        entityOcclusion.disable();
    }

    public static boolean scanContinues(int coordinate, int end, int step) {
        return step > 0 ? coordinate <= end : coordinate >= end;
    }

    private void commitRemoteFootprint(boolean fresh) {
        if (fresh) {
            RemoteFootprint swap = remoteFootprint;
            remoteFootprint = nextRemoteFootprint;
            nextRemoteFootprint = swap;
        } else {
            remoteFootprint.addAll(nextRemoteFootprint);
        }
        nextRemoteFootprint.clear();
    }

    private boolean finishesInSlot(long deadlineNanos) {
        return deadlineNanos == Long.MAX_VALUE
            || (settings.get().finishInSlot() && System.nanoTime() < deadlineNanos);
    }

    private static int frameCode(Frame frame) {
        return frame.getNormal().ordinal() | (frame.getRight().ordinal() << 3) | (frame.getUp().ordinal() << 6);
    }

    private double[] remoteScanBounds(Box area) {
        double[] bounds = scratchRemoteBounds;
        bounds[0] = Double.POSITIVE_INFINITY;
        bounds[1] = Double.POSITIVE_INFINITY;
        bounds[2] = Double.POSITIVE_INFINITY;
        bounds[3] = Double.NEGATIVE_INFINITY;
        bounds[4] = Double.NEGATIVE_INFINITY;
        bounds[5] = Double.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            cellTransform.snappedPointInto((corner & 1) == 0 ? area.getXa() - 1.0D : area.getXb() + 1.0D,
                (corner & 2) == 0 ? area.getYa() - 1.0D : area.getYb() + 1.0D,
                (corner & 4) == 0 ? area.getZa() - 1.0D : area.getZb() + 1.0D, scratchRemotePoint);
            bounds[0] = Math.min(bounds[0], scratchRemotePoint[0]);
            bounds[1] = Math.min(bounds[1], scratchRemotePoint[1]);
            bounds[2] = Math.min(bounds[2], scratchRemotePoint[2]);
            bounds[3] = Math.max(bounds[3], scratchRemotePoint[0]);
            bounds[4] = Math.max(bounds[4], scratchRemotePoint[1]);
            bounds[5] = Math.max(bounds[5], scratchRemotePoint[2]);
        }
        return bounds;
    }

    private boolean localChunkReady(V view, int x, int z) {
        long key = CellKeys.chunkKey(x >> 4, z >> 4);
        byte cached = localChunkReadiness.get(key);
        if (cached != 0) {
            return cached == 1;
        }
        boolean ready = view.isChunkReady(x, z);
        localChunkReadiness.put(key, ready ? (byte) 1 : (byte) 2);
        if (!ready) {
            view.requestChunk(x, z);
        }
        return ready;
    }

    public void updateEntityOcclusionEye(Vec3d eye, ScanDestination<P, V> destination, Frame remoteFrame, boolean frontSide) {
        OpticTransform toward = ViewWindow.of(destination.mirrorMode(), QuarterTurn.of(destination.mirrorRotationQuarterTurns()), portal.origin(),
            portal.frame(), new Vec3d(destination.originX(), destination.originY(), destination.originZ()), remoteFrame, frontSide, 0.0D).toward();
        toward.pointInto(eye.getX(), eye.getY(), eye.getZ(), scratchRemoteEye);
        projectedEntityOcclusion.updateEye(scratchRemoteEye[0], scratchRemoteEye[1], scratchRemoteEye[2]);
    }

    private static int addRejectedCells(int current,
                                        int firstMinimum,
                                        int firstMaximum,
                                        int secondMinimum,
                                        int secondMaximum) {
        if (firstMaximum < firstMinimum || secondMaximum < secondMinimum) {
            return current;
        }
        long count = ((long) firstMaximum - firstMinimum + 1L)
            * ((long) secondMaximum - secondMinimum + 1L);
        return count >= Integer.MAX_VALUE - current ? Integer.MAX_VALUE : current + (int) count;
    }

    private void retainBlockEntity(long key) {
        BlockEntitySample previous = projectedBlockEntities.get(key);
        if (previous != null) {
            nextBlockEntities.put(key, previous);
        }
    }

    private void rememberOcclusionBlocker(long localKey, BlockClaim<B, V> claim, boolean observerOcclusion) {
        if (!observerOcclusion
            || claim.getLightRemoteKey() == BlockClaim.NO_REMOTE_KEY
            || !viewOcclusion.isOccluding(claim.getData())) {
            return;
        }
        long remoteKey = claim.getLightRemoteKey();
        occlusionGeometry.add(remoteKey);
        if (!losingClaimKeys.isEmpty() && losingClaimKeys.contains(localKey)) {
            losingBlockers.add(remoteKey);
        }
    }

    private void addObserverTarget(long localKey, long remoteKey) {
        if (projectedUnresolvedOcclusion.contains(localKey)) {
            unresolvedTargetCells.add(localKey);
            unresolvedTargetRemoteKeys.add(remoteKey);
            return;
        }
        observerTargetCells.add(localKey);
        observerTargetRemoteKeys.add(remoteKey);
    }

    private void retainUnresolvedOcclusion(long key, boolean observerOcclusion) {
        if (observerOcclusion && projectedUnresolvedOcclusion.contains(key)) {
            nextUnresolvedOcclusion.add(key);
        }
    }

    private void clearBlackoutFarFace(int axis, int sign) {
        LongIterator iterator = blackoutBoundary.cells(axis, sign).iterator();
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            if (!blackoutBoundary.containsOther(key, axis, sign)) {
                blackoutGeometry.remove(key);
                blackoutRemoteKeys.remove(key);
            }
        }
        blackoutBoundary.clearFace(axis, sign);
    }

    private static int lateralBlackoutBoundaryMask(int right,
                                                    int up,
                                                    int rightMinimum,
                                                    int rightMaximum,
                                                    int upMinimum,
                                                    int upMaximum,
                                                    int rightAxis,
                                                    int upAxis) {
        int mask = 0;
        if (right == rightMinimum) {
            mask |= BlackoutBoundary.faceMask(rightAxis, -1);
        }
        if (right == rightMaximum) {
            mask |= BlackoutBoundary.faceMask(rightAxis, 1);
        }
        if (up == upMinimum) {
            mask |= BlackoutBoundary.faceMask(upAxis, -1);
        }
        if (up == upMaximum) {
            mask |= BlackoutBoundary.faceMask(upAxis, 1);
        }
        return mask;
    }

    private void rememberBlackoutCell(long key,
                                      long remoteKey,
                                      int boundaryMask,
                                      BlockClaim<B, V> claim) {
        if (!viewOcclusion.isOccluding(claim.getData())) {
            addBlackoutCell(key, remoteKey, boundaryMask);
        }
    }

    private void rememberBlackoutCell(long key,
                                      long remoteKey,
                                      int boundaryMask,
                                      Sample<B, V> sample) {
        if (!viewOcclusion.isOccluding(sample.data)) {
            addBlackoutCell(key, remoteKey, boundaryMask);
        }
    }

    private void addBlackoutCell(long key, long remoteKey, int boundaryMask) {
        if (boundaryMask == 0) {
            return;
        }
        blackoutGeometry.add(key);
        blackoutRemoteKeys.put(key, remoteKey);
        blackoutBoundary.add(key, boundaryMask);
    }

    /** A committed shell claim still covers this cell only while the destination view and the remote cell it sealed are unchanged. */
    private static <B, V> boolean previousShellMatches(BlockClaim<B, V> previous, V destView, long remoteKey) {
        return previous != null
            && previous.isBlackout()
            && previous.getLightRemoteKey() == remoteKey
            && previous.getLightView() == destView;
    }

    private void filterUnresolvedTargets(LongArrayList targetCells, LongArrayList targetRemoteKeys) {
        for (int index = 0; index < targetCells.size(); index++) {
            long localKey = targetCells.getLong(index);
            if (projectedUnresolvedOcclusion.contains(localKey)) {
                resumeTraced++;
                filterObserverTarget(scannedDestinationView,
                    scannedRemoteEyeX, scannedRemoteEyeY, scannedRemoteEyeZ,
                    localKey, targetRemoteKeys.getLong(index));
            }
        }
    }

    private void filterObserverTarget(V view, double eyeX, double eyeY, double eyeZ,
                                      long localKey, long remoteKey) {
        ViewOcclusion.Visibility visibility = viewOcclusion.visibility(view,
            CellKeys.unpackX(remoteKey), CellKeys.unpackY(remoteKey),
            CellKeys.unpackZ(remoteKey), eyeX, eyeY, eyeZ);
        switch (visibility) {
            case HIDDEN -> hideTarget(localKey);
            case UNRESOLVED -> {
                nextUnresolvedOcclusion.add(localKey);
                keepCommittedHold(localKey);
            }
            case VISIBLE -> {
            }
        }
    }

    private void keepCommittedHold(long localKey) {
        if (!holdClaims) {
            return;
        }
        BlockClaim<B, V> current = nextProjected.get(localKey);
        if (current == null || current.isHeld()) {
            return;
        }
        BlockClaim<B, V> previous = projected.get(localKey);
        if (previous == null || !previous.isHeld() || !sameCommittedContent(previous, current)) {
            return;
        }
        long[] blockers = heldBlockers.get(localKey);
        if (blockers == null || anyLosingBlocker(blockers)) {
            return;
        }
        holdClaim(localKey, previous);
        nextHeldBlockers.put(localKey, blockers);
    }

    private void hideTarget(long localKey) {
        BlockClaim<B, V> hidden = nextProjected.get(localKey);
        if (hidden != null && hidden.isBlackout()) {
            return;
        }
        if (hidden != null && holdClaims) {
            BlockClaim<B, V> previous = projected.get(localKey);
            if (previous != null && !previous.isBlackout()) {
                long[] blockers = hiddenHoldBlockers(localKey);
                if (blockers != null) {
                    if (sameCommittedContent(previous, hidden)) {
                        holdClaim(localKey, previous);
                    } else {
                        holdFreshClaim(localKey, hidden);
                    }
                    nextHeldBlockers.put(localKey, blockers);
                    hiddenHolds++;
                    return;
                }
            }
        }
        nextProjected.remove(localKey);
        occlusionRejected++;
    }

    private long[] hiddenHoldBlockers(long localKey) {
        LongArrayList traced = viewOcclusion.hiddenBlockers();
        long[] committed = heldBlockers.get(localKey);
        if (traced.isEmpty()) {
            return committed != null && !anyLosingBlocker(committed) ? committed : null;
        }
        if (anyLosingBlocker(traced)) {
            return null;
        }
        return committed != null && sameBlockers(committed, traced) ? committed : traced.toLongArray();
    }

    private boolean anyLosingBlocker(long[] blockers) {
        if (losingBlockers.isEmpty()) {
            return false;
        }
        for (long blocker : blockers) {
            if (losingBlockers.contains(blocker)) {
                return true;
            }
        }
        return false;
    }

    private boolean anyLosingBlocker(LongArrayList blockers) {
        if (losingBlockers.isEmpty()) {
            return false;
        }
        int size = blockers.size();
        for (int i = 0; i < size; i++) {
            if (losingBlockers.contains(blockers.getLong(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameBlockers(long[] committed, LongArrayList traced) {
        int size = traced.size();
        if (committed.length != size) {
            return false;
        }
        for (int i = 0; i < size; i++) {
            if (committed[i] != traced.getLong(i)) {
                return false;
            }
        }
        return true;
    }

    private static <B, V> boolean sameCommittedContent(BlockClaim<B, V> previous, BlockClaim<B, V> current) {
        return current.sameBlock(previous) && current.sameLightSource(previous)
            && current.getLightRemoteKey() == previous.getLightRemoteKey();
    }

    private void holdFreshClaim(long key, BlockClaim<B, V> fresh) {
        nextProjected.put(key, fresh.withHeld(true));
        long since = heldSince.get(key);
        nextHeldSince.put(key, since == Long.MIN_VALUE ? holdGeneration : since);
        if (deltaBaseline != null) {
            changedClaimKeys.add(key);
        }
    }

    private void holdClaim(long key, BlockClaim<B, V> previous) {
        BlockClaim<B, V> held = previous.withHeld(true);
        nextProjected.put(key, held);
        nextBlockEntities.remove(key);
        retainBlockEntity(key);
        long since = heldSince.get(key);
        nextHeldSince.put(key, since == Long.MIN_VALUE ? holdGeneration : since);
        if (deltaBaseline == null) {
            return;
        }
        if (held != previous) {
            changedClaimKeys.add(key);
        } else {
            changedClaimKeys.remove(key);
        }
    }

    private boolean beginHolding(boolean committed) {
        passRevokesConeHolds = coneHoldsRevoked;
        passExposesHolds = holdsExposed;
        passDropsHolds = dropHoldsRequested;
        coneHoldsRevoked = false;
        holdsExposed = false;
        dropHoldsRequested = false;
        ScanSettings current = settings.get();
        maxHeldClaims = current.maxHeldClaims();
        holdGeneration++;
        hiddenHolds = 0;
        coneHolds = 0;
        heldEvictions = 0;
        removedClaimsResolved = false;
        boolean enabled = committed && current.holdInvisibleClaims() && maxHeldClaims > 0 && !passDropsHolds;
        holdConeClaims = enabled && !passRevokesConeHolds;
        return enabled;
    }

    private void clearPassHoldRequests() {
        passRevokesConeHolds = false;
        passExposesHolds = false;
        passDropsHolds = false;
    }

    private void evictOverflowHeldClaims() {
        int excess = nextHeldSince.size() - maxHeldClaims;
        if (excess <= 0) {
            return;
        }
        int size = nextHeldSince.size();
        long[] keys = new long[size];
        long[] generations = new long[size];
        int index = 0;
        ObjectIterator<Long2LongMap.Entry> iterator = nextHeldSince.long2LongEntrySet().fastIterator();
        while (iterator.hasNext()) {
            Long2LongMap.Entry entry = iterator.next();
            keys[index] = entry.getLongKey();
            generations[index] = entry.getLongValue();
            index++;
        }
        long[] sorted = generations.clone();
        Arrays.sort(sorted);
        long threshold = sorted[excess - 1];
        int thresholdBudget = excess;
        for (int i = 0; i < size; i++) {
            if (generations[i] < threshold) {
                thresholdBudget--;
            }
        }
        for (int i = 0; i < size; i++) {
            if (generations[i] < threshold) {
                evictHeldClaim(keys[i]);
            } else if (generations[i] == threshold && thresholdBudget > 0) {
                thresholdBudget--;
                evictHeldClaim(keys[i]);
            }
        }
    }

    private void evictHeldClaim(long key) {
        nextProjected.remove(key);
        nextHeldSince.remove(key);
        nextHeldBlockers.remove(key);
        nextBlockEntities.remove(key);
        changedClaimKeys.remove(key);
        if (deltaBaseline != null && deltaBaseline.containsKey(key)) {
            removedClaimKeys.add(key);
        }
        heldEvictions++;
    }

    private void recountProjectionChanges(boolean forceFullSend) {
        enterCount = 0;
        keptCount = 0;
        maskedCells = 0;
        int retainedKeys = 0;
        ObjectIterator<Long2ObjectMap.Entry<BlockClaim<B, V>>> iterator = nextProjected.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            Long2ObjectMap.Entry<BlockClaim<B, V>> entry = iterator.next();
            BlockClaim<B, V> nextCell = entry.getValue();
            BlockClaim<B, V> previousCell = projected.get(entry.getLongKey());
            if (nextCell.isMaskAir()) {
                maskedCells++;
            }
            if (previousCell != null) {
                retainedKeys++;
            }
            boolean unchanged = previousCell != null
                && nextCell.getData().equals(previousCell.getData())
                && nextCell.isMaskAir() == previousCell.isMaskAir()
                && nextCell.sameLightSource(previousCell);
            if (!forceFullSend && unchanged) {
                keptCount++;
            } else {
                enterCount++;
            }
        }
        exitCount = projected.size() - retainedKeys;
    }

    private record ScanRequest<B, P extends Endpoint, V>(ScanDestination<P, V> destination,
                               Frame targetFrame,
                               Vec3d eye,
                               ViewVolume frustum,
                               double depthBlocks,
                               boolean forceStableCellResample,
                               boolean forceFullSend,
                               boolean refreshObserverVisibility,
                               ScanMode mode,
                               ViewPlate<B> plate,
                               boolean blockEntities,
                               LodPolicy lod) {
    }

    private final class ScanPass implements LocalOccupancy {
        private V localView;
        private V destView;
        private P dest;
        private ViewVolume frustum;
        private ViewPlate<B> plate;
        private Frame localFrame;
        private RecursiveEndpoints<W, P>.Index rootRecursiveIndex;
        private RecursiveEndpoints.Hit<W, P> maskHit;
        private PlaneWindow planeWindow;
        private PlaneWindow blackoutWindow;
        private HoldProof holdProof;
        private LodPolicy lodPolicy;
        private int[] axisMin;
        private int[] axisMax;
        private int[] cellCoords;
        private double[] axisOrigin;
        private double[] slabWindowBounds;
        private boolean mirrorMode;
        private boolean forceStableCellResample;
        private boolean forceFullSend;
        private boolean refreshObserverVisibility;
        private boolean buriedCellCulling;
        private boolean blockEntities;
        private boolean eyeFrontSide;
        private boolean recursiveGeometry;
        private boolean maskGeometry;
        private boolean maskRow;
        private int maskLow;
        private int maskHigh;
        private int maskRowCandidateCount;
        private boolean cacheEmptyCells;
        private boolean skipKnownEmptyCells;
        private boolean blackoutEnabled;
        private B blackoutData;
        private boolean observerOcclusion;
        private boolean blackoutFarSliceFound;
        private boolean lodActive;
        private boolean reuseMappedClaims;
        private boolean mergedSlab;
        private boolean windowContainsRow;
        private boolean frustumContainsRow;
        private boolean frustumRowPrepared;
        private boolean blackoutContainsRow;
        private int mirrorRotationQuarterTurns;
        private int localMinY;
        private int localMaxY;
        private int normalAxis;
        private int blackoutFarSign;
        private int rightAxis;
        private int rightSign;
        private int upAxis;
        private int upSign;
        private int normalStep;
        private int normalStart;
        private int normalEnd;
        private int blackoutFarCoordinate;
        private int rightBlockMin;
        private int rightBlockMax;
        private int upBlockMin;
        private int upBlockMax;
        private int slabIndex;
        private int rightStart;
        private int rightEnd;
        private int upStart;
        private int upEnd;
        private double eyeX;
        private double eyeY;
        private double eyeZ;
        private double remoteOriginX;
        private double remoteOriginY;
        private double remoteOriginZ;
        private ApertureSlab volume;
        private double projectionFacingNormal;
        private double slabSignedDistance;
        private double sampleNormalCenter;
        private final int recursiveDepth;
        private int n;
        private int r;
        private int u;
        private int deadlineCells;
        private boolean slabReady;
        private boolean rowReady;
        private boolean geometryComplete;
        private boolean ready;
        private boolean filterTargets;
        private int finishStage;
        private int finishTargetIndex;
        private int finishDeadlineTargets;
        private final boolean freshRemoteFootprint;

        private ScanPass(ScanRequest<B, P, V> request) {
            ScanDestination<P, V> destination = request.destination();
            Frame targetFrame = request.targetFrame();
            Vec3d eye = request.eye();
            frustum = request.frustum();
            double depthBlocks = request.depthBlocks();
            forceStableCellResample = request.forceStableCellResample();
            forceFullSend = request.forceFullSend();
            refreshObserverVisibility = request.refreshObserverVisibility();
            ScanMode mode = request.mode();
            buriedCellCulling = mode.buriedCellCulling();
            plate = request.plate();
            blockEntities = request.blockEntities();
            LodPolicy lod = request.lod();
            recursiveDepth = settings.get().recursiveDepth();
            blackoutData = blackout.data();
            localView = destination.localView();
            destView = destination.destView();
            dest = destination.dest();
            mirrorMode = destination.mirrorMode();
            mirrorRotationQuarterTurns = destination.mirrorRotationQuarterTurns();
            freshRemoteFootprint = restartRemoteFootprint;
            restartRemoteFootprint = false;
            nextRemoteFootprint.clear();

            boolean reuseCommittedContent = scanCommitted && completeGeometry
                && !forceStableCellResample && !forceFullSend
                && localView == scannedLocalView && destView == scannedDestinationView
                && localView.getRevision() == scannedLocalRevision
                && destView.getRevision() == scannedDestinationRevision;
            deltaBaseline = scanCommitted && !forceFullSend ? projected : null;
            holdClaims = beginHolding(scanCommitted && destView == scannedDestinationView
                && destView.getRevision() == scannedDestinationRevision);
            holdConeClaims = holdConeClaims && localView == scannedLocalView
                && localView.getRevision() == scannedLocalRevision;
            nextHeldSince.clear();
            nextHeldBlockers.clear();
            losingBlockers.clear();
            retainedClaimCount = 0;
            unfilteredClaimCount = 0;
            changedClaimKeys.clear();
            removedClaimKeys.clear();
            if (forceStableCellResample || forceFullSend
                || localView != scannedLocalView || destView != scannedDestinationView
                || localView.getRevision() != scannedLocalRevision
                || destView.getRevision() != scannedDestinationRevision) {
                emptyCells.clear();
            }
            emptyCellSkips = 0;
            completeGeometry = true;
            scanCommitted = false;
            scannedLocalView = localView;
            scannedDestinationView = destView;
            scannedFrustum = frustum;
            scannedLocalRevision = localView.getRevision();
            scannedDestinationRevision = destView.getRevision();
            scannedEyeX = eye.getX();
            scannedEyeY = eye.getY();
            scannedEyeZ = eye.getZ();
            scannedRevealMargin = settings.get().revealMarginDegrees();
            scannedBlackout = blackout.isEnabled();

            localChunkReadiness.clear();
            nextProjected.clear();
            nextBlockEntities.clear();
            blackoutGeometry.clear();
            blackoutBoundary.clear();
            blackoutRemoteKeys.clear();
            occlusionGeometry.clear();
            observerTargetCells.clear();
            observerTargetRemoteKeys.clear();
            unresolvedTargetCells.clear();
            unresolvedTargetRemoteKeys.clear();
            nextUnresolvedOcclusion.clear();
            enterCount = 0;
            keptCount = 0;

            localMinY = localView.getMinHeight();
            localMaxY = localView.getMaxHeight() - 1;
            Box area = frustum.getRegion();
            int xa = ApertureSlab.minBlockForCenter(area.getXa());
            int ya = Math.max(ApertureSlab.minBlockForCenter(area.getYa()), localMinY);
            int za = ApertureSlab.minBlockForCenter(area.getZa());
            int xb = ApertureSlab.maxBlockForCenter(area.getXb());
            int yb = Math.min(ApertureSlab.maxBlockForCenter(area.getYb()), localMaxY);
            int zb = ApertureSlab.maxBlockForCenter(area.getZb());

            localFrame = portal.frame();
            Frame remoteFrame = targetFrame != null
                ? targetFrame
                : mirrorMode ? localFrame.flipNormal() : destination.destAnchor().frame();
            double localOriginX = portal.origin().getX();
            double localOriginY = portal.origin().getY();
            double localOriginZ = portal.origin().getZ();
            remoteOriginX = mirrorMode ? localOriginX : destination.originX();
            remoteOriginY = mirrorMode ? localOriginY : destination.originY();
            remoteOriginZ = mirrorMode ? localOriginZ : destination.originZ();

            double facingX = localFrame.getNormal().x();
            double facingY = localFrame.getNormal().y();
            double facingZ = localFrame.getNormal().z();
            eyeX = eye.getX();
            eyeY = eye.getY();
            eyeZ = eye.getZ();
            double eyeRelX = eyeX - localOriginX;
            double eyeRelY = eyeY - localOriginY;
            double eyeRelZ = eyeZ - localOriginZ;
            eyeFrontSide = ApertureSlab.side(localFrame, localOriginX, localOriginY, localOriginZ, eyeX, eyeY, eyeZ);
            projectionLocalFrame = localFrame.view(eyeFrontSide);
            projectionRemoteFrame = remoteFrame.view(eyeFrontSide);
            cellTransform = ViewWindow.of(mirrorMode, QuarterTurn.of(mirrorRotationQuarterTurns), portal.origin(), localFrame,
                new Vec3d(remoteOriginX, remoteOriginY, remoteOriginZ), remoteFrame, eyeFrontSide, 0.0D).toward();
            cellTransform.pointInto(eyeX, eyeY, eyeZ, scratchRemoteEye);
            sampler.prepareTransform(cellTransform.permutation().inverse());
            scannedRemoteEyeX = scratchRemoteEye[0];
            scannedRemoteEyeY = scratchRemoteEye[1];
            scannedRemoteEyeZ = scratchRemoteEye[2];
            prepareRecursiveGeometry(area);
            if (recursiveGeometry) {
                nextRemoteFootprint.markNested();
            }
            cacheEmptyCells = !blackout.isEnabled() && !recursiveGeometry;
            if (!cacheEmptyCells) {
                emptyCells.clear();
            }
            skipKnownEmptyCells = cacheEmptyCells && !emptyCells.isEmpty();
            double projectionFacingX = projectionLocalFrame.getNormal().x();
            double projectionFacingY = projectionLocalFrame.getNormal().y();
            double projectionFacingZ = projectionLocalFrame.getNormal().z();
            projectionEyeDot = (eyeRelX * projectionFacingX) + (eyeRelY * projectionFacingY) + (eyeRelZ * projectionFacingZ);
            blackoutEnabled = blackout.isEnabled() && blackoutData != null;
            volume = ApertureSlab.of(aperture.getArea(), localFrame,
                ApertureSlab.plane(localFrame, localOriginX, localOriginY, localOriginZ), eyeFrontSide, depthBlocks, 0.0D);
            planeWindow = PlaneWindow.create(aperture, aperture.getArea(), projectionLocalFrame,
                localOriginX, localOriginY, localOriginZ, settings.get().aperturePadding(),
                projectionEyeDot);
            blackoutWindow = blackoutEnabled
                ? PlaneWindow.create(aperture, aperture.getArea(), projectionLocalFrame,
                    localOriginX, localOriginY, localOriginZ, 0.0D, projectionEyeDot)
                : null;
            holdProof = holdConeClaims
                ? HoldProof.create(aperture.getArea(), projectionLocalFrame,
                    localOriginX, localOriginY, localOriginZ, settings.get().aperturePadding())
                : null;
            planeRejected = 0;
            windowRejected = 0;
            frustumRejected = 0;
            frustumMaskedRows = 0;
            frustumScalarRows = 0;
            occlusionRejected = 0;
            maskedCells = 0;
            plateHits = 0;

            if (facingX != 0.0D) {
                xa = Math.max(xa, volume.normalMin());
                xb = Math.min(xb, volume.normalMax());
            } else if (facingY != 0.0D) {
                ya = Math.max(ya, volume.normalMin());
                yb = Math.min(yb, volume.normalMax());
            } else {
                za = Math.max(za, volume.normalMin());
                zb = Math.min(zb, volume.normalMax());
            }

            Face projectionNormalDirection = projectionLocalFrame.getNormal();
            Face projectionRightDirection = projectionLocalFrame.getRight();
            Face projectionUpDirection = projectionLocalFrame.getUp();
            normalAxis = projectionNormalDirection.axisIndex();
            blackoutFarSign = -(projectionNormalDirection.x()
                + projectionNormalDirection.y() + projectionNormalDirection.z());
            rightAxis = projectionRightDirection.axisIndex();
            rightSign = projectionRightDirection.x() + projectionRightDirection.y() + projectionRightDirection.z();
            upAxis = projectionUpDirection.axisIndex();
            upSign = projectionUpDirection.x() + projectionUpDirection.y() + projectionUpDirection.z();
            axisMin = scratchAxisMin;
            axisMin[0] = xa;
            axisMin[1] = ya;
            axisMin[2] = za;
            axisMax = scratchAxisMax;
            axisMax[0] = xb;
            axisMax[1] = yb;
            axisMax[2] = zb;
            axisOrigin = scratchAxisOrigin;
            axisOrigin[0] = localOriginX;
            axisOrigin[1] = localOriginY;
            axisOrigin[2] = localOriginZ;
            projectionFacingNormal = normalAxis == 0 ? projectionFacingX : (normalAxis == 1 ? projectionFacingY : projectionFacingZ);
            slabWindowBounds = scratchSlabWindowBounds;
            cellCoords = scratchCellCoords;
            observerOcclusion = mode.observerOcclusion();
            normalStep = projectionFacingNormal > 0.0D ? -1 : 1;
            normalStart = normalStep > 0 ? axisMin[normalAxis] : axisMax[normalAxis];
            normalEnd = normalStep > 0 ? axisMax[normalAxis] : axisMin[normalAxis];
            blackoutFarCoordinate = 0;
            blackoutFarSliceFound = false;
            lodPolicy = lod == null ? LodPolicy.NONE : lod;
            lodActive = !lodPolicy.isNone();
            CellMapping mapping = new CellMapping(frameCode(projectionLocalFrame), frameCode(projectionRemoteFrame), frameCode(localFrame),
                localOriginX, localOriginY, localOriginZ, remoteOriginX, remoteOriginY, remoteOriginZ,
                mirrorMode, mirrorRotationQuarterTurns, volume.clearance(),
                lodPolicy.mergeRuns(), lodPolicy.distanceBlocks(), lodPolicy.detailCutoffBlocks());
            reuseMappedClaims = reuseCommittedContent && !recursiveGeometry && mapping.equals(scannedMapping);
            scannedMapping = mapping;

            n = normalStart;
        }

        private void prepareRecursiveGeometry(Box area) {
            recursiveGeometry = false;
            maskGeometry = false;
            maskRow = false;
            maskCandidates.clear();
            W destSampleW = sampler.world(destView);
            if (destSampleW == null || recursiveDepth < 0) {
                rootRecursiveIndex = null;
                maskHit = null;
                return;
            }
            double[] bounds = remoteScanBounds(area);
            if (!sampler.recursivePortalsReach(destSampleW, dest, bounds)) {
                rootRecursiveIndex = sampler.emptyRecursiveIndex();
                maskHit = null;
                return;
            }
            rootRecursiveIndex = sampler.recursiveIndex(destSampleW, scratchRemoteEye[0], scratchRemoteEye[1], scratchRemoteEye[2], dest);
            maskHit = rootRecursiveIndex.maskHit();
            RecursiveEndpoints.Reach reach = rootRecursiveIndex.reach(bounds[0], bounds[1], bounds[2],
                bounds[3], bounds[4], bounds[5], recursiveDepth, maskCandidates);
            recursiveGeometry = reach == RecursiveEndpoints.Reach.RECURSIVE;
            maskGeometry = reach == RecursiveEndpoints.Reach.MASK;
            if (maskGeometry && maskRowCandidates.length < maskCandidates.size()) {
                maskRowCandidates = new int[maskCandidates.size()];
                maskRowLows = new int[maskCandidates.size()];
                maskRowHighs = new int[maskCandidates.size()];
            }
        }

        private void prepareMaskRow() {
            maskRow = false;
            maskRowCandidateCount = 0;
            rowSamplePoint(upStart, scratchMaskRowStart);
            rowSamplePoint(upStart + 1, scratchMaskRowNext);
            double directionX = scratchMaskRowNext[0] - scratchMaskRowStart[0];
            double directionY = scratchMaskRowNext[1] - scratchMaskRowStart[1];
            double directionZ = scratchMaskRowNext[2] - scratchMaskRowStart[2];
            double baseX = scratchMaskRowStart[0] - (upStart * directionX);
            double baseY = scratchMaskRowStart[1] - (upStart * directionY);
            double baseZ = scratchMaskRowStart[2] - (upStart * directionZ);
            int rowLow = Math.min(upStart, upEnd);
            int rowHigh = Math.max(upStart, upEnd);
            maskLow = Integer.MAX_VALUE;
            maskHigh = Integer.MIN_VALUE;
            for (int candidateIndex = 0; candidateIndex < maskCandidates.size(); candidateIndex++) {
                scratchMaskRange[0] = rowLow - 1.0D;
                scratchMaskRange[1] = rowHigh + 1.0D;
                if (!maskCandidates.get(candidateIndex).clipLine(baseX, baseY, baseZ, directionX, directionY, directionZ, scratchMaskRange)) {
                    continue;
                }
                int low = Math.max(rowLow, (int) Math.floor(scratchMaskRange[0]) - 1);
                int high = Math.min(rowHigh, (int) Math.ceil(scratchMaskRange[1]) + 1);
                if (low > high) {
                    continue;
                }
                maskRowCandidates[maskRowCandidateCount] = candidateIndex;
                maskRowLows[maskRowCandidateCount] = low;
                maskRowHighs[maskRowCandidateCount] = high;
                maskRowCandidateCount++;
                maskLow = Math.min(maskLow, low);
                maskHigh = Math.max(maskHigh, high);
            }
            maskRow = maskRowCandidateCount > 0;
        }

        private void rowSamplePoint(int upCoordinate, double[] out) {
            double normalCenter = sampleNormalCenter;
            double rightCenter = r + 0.5D;
            double upCenter = upCoordinate + 0.5D;
            cellTransform.snappedPointInto(normalAxis == 0 ? normalCenter : rightAxis == 0 ? rightCenter : upCenter,
                normalAxis == 1 ? normalCenter : rightAxis == 1 ? rightCenter : upCenter,
                normalAxis == 2 ? normalCenter : rightAxis == 2 ? rightCenter : upCenter, out);
        }

        private int maskSkipLimit(int coordinate, int candidate) {
            if (coordinate >= maskLow && coordinate <= maskHigh) {
                return coordinate;
            }
            if (upSign > 0) {
                return coordinate < maskLow && candidate > maskLow ? maskLow : candidate;
            }
            return coordinate > maskHigh && candidate < maskHigh ? maskHigh : candidate;
        }

        private boolean masksRemotePoint(int coordinate) {
            for (int index = 0; index < maskRowCandidateCount; index++) {
                if (coordinate >= maskRowLows[index] && coordinate <= maskRowHighs[index]
                    && maskCandidates.get(maskRowCandidates[index]).covers(scratchRemotePoint[0], scratchRemotePoint[1], scratchRemotePoint[2])) {
                    return true;
                }
            }
            return false;
        }

        private void applyRemotePoint(double cx, double cy, double cz) {
            if (mergedSlab) {
                cellTransform.snappedPointInto(normalAxis == 0 ? sampleNormalCenter : cx,
                    normalAxis == 1 ? sampleNormalCenter : cy,
                    normalAxis == 2 ? sampleNormalCenter : cz, scratchRemotePoint);
            } else {
                cellTransform.snappedPointInto(cx, cy, cz, scratchRemotePoint);
            }
        }

        private boolean advanceGeometry(long deadlineNanos) {
            for (; scanContinues(n, normalEnd, normalStep); n += normalStep, slabReady = false, rowReady = false) {
                if (!slabReady) {
                    slabSignedDistance = projectionFacingNormal * ((n + 0.5D) - axisOrigin[normalAxis]);
                    if (!planeWindow.slabWindow(eyeX, eyeY, eyeZ, slabSignedDistance, slabWindowBounds)) {
                        continue;
                    }
                    rightBlockMin = PlaneWindow.slabBlockMin(slabWindowBounds[0], slabWindowBounds[1], rightSign, axisOrigin[rightAxis], axisMin[rightAxis]);
                    rightBlockMax = PlaneWindow.slabBlockMax(slabWindowBounds[0], slabWindowBounds[1], rightSign, axisOrigin[rightAxis], axisMax[rightAxis]);
                    upBlockMin = PlaneWindow.slabBlockMin(slabWindowBounds[2], slabWindowBounds[3], upSign, axisOrigin[upAxis], axisMin[upAxis]);
                    upBlockMax = PlaneWindow.slabBlockMax(slabWindowBounds[2], slabWindowBounds[3], upSign, axisOrigin[upAxis], axisMax[upAxis]);
                    if (!volume.containsSlab(n)) {
                        planeRejected = addRejectedCells(
                            planeRejected, rightBlockMin, rightBlockMax, upBlockMin, upBlockMax);
                        continue;
                    }
                    slabIndex = volume.depthIndex(n);
                    mergedSlab = lodActive && lodPolicy.mergesSlab(slabIndex);
                    sampleNormalCenter = mergedSlab ? (n - normalStep) + 0.5D : n + 0.5D;
                    rightStart = rightSign > 0 ? rightBlockMin : rightBlockMax;
                    rightEnd = rightSign > 0 ? rightBlockMax : rightBlockMin;
                    upStart = upSign > 0 ? upBlockMin : upBlockMax;
                    upEnd = upSign > 0 ? upBlockMax : upBlockMin;
                    cellCoords[normalAxis] = n;
                    r = rightStart;
                    slabReady = true;
                }
                for (; scanContinues(r, rightEnd, rightSign); r += rightSign, rowReady = false) {
                    if (!rowReady) {
                        cellCoords[rightAxis] = r;
                        cellCoords[upAxis] = upStart;
                        double rowX = cellCoords[0] + 0.5D;
                        double rowY = cellCoords[1] + 0.5D;
                        double rowZ = cellCoords[2] + 0.5D;
                        double rowEnd = upEnd + 0.5D;
                        windowContainsRow = planeWindow.containsRow(upAxis, eyeX, eyeY, eyeZ,
                            rowX, rowY, rowZ, rowEnd, slabSignedDistance);
                        if (!windowContainsRow) {
                            planeWindow.prepareRow(upAxis, eyeX, eyeY, eyeZ, rowX, rowY, rowZ, slabSignedDistance);
                        }
                        frustumContainsRow = frustum.containsRow(upAxis, rowX, rowY, rowZ, rowEnd);
                        frustumRowPrepared = !frustumContainsRow
                            && frustumRow.prepare(frustum, upAxis, rowX, rowY, rowZ, upStart, upEnd);
                        if (frustumRowPrepared) {
                            frustumMaskedRows++;
                        } else if (!frustumContainsRow) {
                            frustumScalarRows++;
                        }
                        blackoutContainsRow = blackoutEnabled
                            && blackoutWindow.containsRow(upAxis, eyeX, eyeY, eyeZ,
                                rowX, rowY, rowZ, rowEnd, slabSignedDistance);
                        if (cacheEmptyCells) {
                            emptyCells.beginRow(upAxis, cellCoords);
                        }
                        if (maskGeometry) {
                            prepareMaskRow();
                        }
                        u = upStart;
                        rowReady = true;
                    }
                    for (; scanContinues(u, upEnd, upSign); u += upSign) {
                        if (++deadlineCells > 128) {
                            deadlineCells = 1;
                            if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos) {
                                return false;
                            }
                        }
                        if (skipKnownEmptyCells) {
                            int candidate = emptyCells.nextCandidate(u, upEnd, upSign);
                            if (maskRow) {
                                candidate = maskSkipLimit(u, candidate);
                            }
                            emptyCellSkips += Math.min(Math.abs(candidate - u), Math.abs(upEnd - u) + 1);
                            u = candidate;
                            if (!scanContinues(u, upEnd, upSign)) {
                                break;
                            }
                        }
                        cellCoords[upAxis] = u;
                        int x = cellCoords[0];
                        int y = cellCoords[1];
                        int z = cellCoords[2];
                        double cx = x + 0.5D;
                        double cy = y + 0.5D;
                        double cz = z + 0.5D;

                        if (!windowContainsRow && !planeWindow.containsRowCell(u)) {
                            windowRejected++;
                            continue;
                        }

                        if (!frustumContainsRow && !(frustumRowPrepared
                            ? frustumRow.contains(u) : frustum.containsPrimitive(cx, cy, cz))) {
                            frustumRejected++;
                            continue;
                        }

                        boolean masked = false;
                        boolean remotePointMapped = maskRow && u >= maskLow && u <= maskHigh;
                        if (remotePointMapped) {
                            applyRemotePoint(cx, cy, cz);
                            masked = masksRemotePoint(u);
                        }

                        long key = CellKeys.pack(x, y, z);
                        BlockClaim<B, V> previousCell = projected.get(key);
                        boolean blackoutFarCell = blackoutEnabled
                            && (blackoutContainsRow || blackoutWindow.containsRayIntersection(
                                eyeX, eyeY, eyeZ, cx, cy, cz, slabSignedDistance));
                        int blackoutBoundaryMask = blackoutEnabled
                            ? lateralBlackoutBoundaryMask(r, u, rightBlockMin, rightBlockMax,
                                upBlockMin, upBlockMax, rightAxis, upAxis)
                            : 0;
                        if (blackoutBoundaryMask != 0 && blackoutWindow.intersectsBlockSilhouette(
                            eyeX, eyeY, eyeZ, cx, cy, cz, slabSignedDistance)) {
                            blackoutBoundaryMask = 0;
                        }
                        if (blackoutFarCell && (!blackoutFarSliceFound || blackoutFarCoordinate != n)) {
                            clearBlackoutFarFace(normalAxis, blackoutFarSign);
                            blackoutFarCoordinate = n;
                            blackoutFarSliceFound = true;
                        }
                        if (blackoutFarCell) {
                            blackoutBoundaryMask |= BlackoutBoundary.faceMask(normalAxis, blackoutFarSign);
                        }
                        boolean blackoutCell = blackoutBoundaryMask != 0;
                        if (reuseMappedClaims && !masked && previousCell != null && !previousCell.isBlackout() && !previousCell.isHeld()
                            && previousCell.getLightView() == destView
                            && previousCell.isFullBright() == blackoutEnabled) {
                            long remoteKey = previousCell.getLightRemoteKey();
                            if (remoteKey != BlockClaim.NO_REMOTE_KEY) {
                                nextRemoteFootprint.recordCell(remoteKey);
                            }
                            nextProjected.put(key, previousCell);
                            retainedClaimCount++;
                            retainBlockEntity(key);
                            rememberOcclusionBlocker(key, previousCell, observerOcclusion);
                            if (!localChunkReady(localView, x, z)) {
                                completeGeometry = false;
                                retainUnresolvedOcclusion(key, observerOcclusion);
                            } else if (observerOcclusion
                                && (refreshObserverVisibility || projectedUnresolvedOcclusion.contains(key))) {
                                addObserverTarget(key, remoteKey);
                            }
                            if (blackoutCell) {
                                rememberBlackoutCell(key, remoteKey, blackoutBoundaryMask, previousCell);
                            }
                            continue;
                        }
                        if (!remotePointMapped) {
                            applyRemotePoint(cx, cy, cz);
                        }

                        int rx = (int) Math.floor(scratchRemotePoint[0]);
                        int ry = (int) Math.floor(scratchRemotePoint[1]);
                        int rz = (int) Math.floor(scratchRemotePoint[2]);
                        long remoteKey = CellKeys.pack(rx, ry, rz);
                        nextRemoteFootprint.record(rx, ry, rz);
                        long previousRemoteKey = previousCell == null
                            ? BlockClaim.NO_REMOTE_KEY
                            : previousCell.getLightRemoteKey();
                        if (!localChunkReady(localView, x, z)) {
                            completeGeometry = false;
                            if (previousCell != null && !previousCell.isBlackout()) {
                                BlockClaim<B, V> retained = previousCell.withFullBright(blackoutEnabled).withHeld(false);
                                nextProjected.put(key, retained);
                                retainedClaimCount++;
                                if (deltaBaseline != null && retained != previousCell) {
                                    changedClaimKeys.add(key);
                                }
                                retainBlockEntity(key);
                                rememberOcclusionBlocker(key, retained, observerOcclusion);
                                retainUnresolvedOcclusion(key, observerOcclusion);
                                if (blackoutCell) {
                                    rememberBlackoutCell(key, remoteKey, blackoutBoundaryMask, retained);
                                }
                            } else if (blackoutCell && previousShellMatches(previousCell, destView, remoteKey)) {
                                addBlackoutCell(key, remoteKey, blackoutBoundaryMask);
                            }
                            continue;
                        }
                        boolean previousLightingMatches = previousCell != null
                            && !previousCell.isBlackout()
                            && !previousCell.isHeld()
                            && previousCell.isFullBright() == blackoutEnabled;
                        if (previousLightingMatches && previousRemoteKey == remoteKey
                            && previousCell.getLightView() == destView) {
                            if (!forceStableCellResample && !forceFullSend && !masked
                                && (!refreshObserverVisibility || !recursiveGeometry)) {
                                nextProjected.put(key, previousCell);
                                retainedClaimCount++;
                                retainBlockEntity(key);
                                rememberOcclusionBlocker(key, previousCell, observerOcclusion);
                                if (observerOcclusion && (refreshObserverVisibility || projectedUnresolvedOcclusion.contains(key))) {
                                    addObserverTarget(key, remoteKey);
                                }
                                if (blackoutCell) {
                                    rememberBlackoutCell(key, remoteKey, blackoutBoundaryMask, previousCell);
                                }
                                continue;
                            }
                        }

                        RecursiveEndpoints.Hit<W, P> recursiveHit = recursiveGeometry
                            ? rootRecursiveIndex.find(scratchRemotePoint[0], scratchRemotePoint[1], scratchRemotePoint[2],
                                recursiveDepth)
                            : masked ? maskHit : null;
                        PlateCell<B> plateCell = plate == null || recursiveHit != null ? null : plate.cleanCell(key, rx, rz);
                        Sample<B, V> sample;
                        if (plateCell != null) {
                            plateHits++;
                            sample = plateCell.sample(destView, remoteKey);
                        } else {
                            sample = sampler.resolve(destView,
                                scratchRemotePoint[0], scratchRemotePoint[1], scratchRemotePoint[2],
                                scannedRemoteEyeX, scannedRemoteEyeY, scannedRemoteEyeZ,
                                dest,
                                recursiveDepth,
                                buriedCellCulling,
                                rootRecursiveIndex,
                                recursiveHit);
                            if (lodActive && recursiveHit == null && sample.kind == Sample.Kind.BLOCK
                                && lodPolicy.dropsDetail(mergedSlab ? slabIndex - 1 : slabIndex, memo.blocks().materialName(memo.blocks().material(sample.data)))) {
                                sample = new Sample<B, V>(Sample.Kind.REMOTE_AIR, sampler.air(), destView, sample.remoteKey());
                            }
                        }
                        if (sample.kind == Sample.Kind.OCCLUDED) {
                            if (cacheEmptyCells) {
                                emptyCells.markEmpty(u);
                            }
                            continue;
                        }
                        if (sample.kind == Sample.Kind.NO_SAMPLE) {
                            completeGeometry = false;
                            boolean matchingRemoteUnavailable = !destView.isChunkReady(rx, rz)
                                && previousCell != null
                                && previousRemoteKey == remoteKey;
                            if (matchingRemoteUnavailable && !previousCell.isBlackout()) {
                                BlockClaim<B, V> retained = previousCell.withFullBright(blackoutEnabled).withHeld(false);
                                nextProjected.put(key, retained);
                                retainedClaimCount++;
                                if (deltaBaseline != null && retained != previousCell) {
                                    changedClaimKeys.add(key);
                                }
                                retainBlockEntity(key);
                                rememberOcclusionBlocker(key, retained, observerOcclusion);
                                retainUnresolvedOcclusion(key, observerOcclusion);
                                if (blackoutCell) {
                                    rememberBlackoutCell(key, remoteKey, blackoutBoundaryMask, retained);
                                }
                            } else if (blackoutCell && previousShellMatches(previousCell, destView, remoteKey)) {
                                addBlackoutCell(key, remoteKey, blackoutBoundaryMask);
                            }
                            continue;
                        }
                        if (blackoutCell) {
                            rememberBlackoutCell(key, remoteKey, blackoutBoundaryMask, sample);
                        }
                        boolean maskAir = sample.kind == Sample.Kind.MASK_AIR;
                        boolean remoteAir = sample.kind == Sample.Kind.REMOTE_AIR;
                        boolean localAir = (maskAir || remoteAir) && memo.isLocalAir(localView, x, y, z);
                        if ((maskAir || remoteAir) && !shouldProjectAirSample(sample.kind, localAir)) {
                            if (cacheEmptyCells && !maskAir) {
                                emptyCells.markEmpty(u);
                            }
                            continue;
                        }
                        B projectedHit;
                        if (maskAir || remoteAir) {
                            projectedHit = sampler.air();
                        } else if (plateCell != null) {
                            projectedHit = plateCell.data();
                        } else {
                            projectedHit = sampler.transformProjectedBlockData(sample.data);
                        }

                        BlockClaim<B, V> nextCell;
                        if (blackoutEnabled) {
                            BlockClaim.LightingPolicy lightingPolicy = BlockClaim.LightingPolicy.FULL_BRIGHT;
                            nextCell = sample.matchesClaim(previousCell, projectedHit, maskAir, lightingPolicy)
                                ? previousCell
                                : sample.asClaim(projectedHit, lightingPolicy);
                        } else {
                            nextCell = sample.matchesClaim(previousCell, projectedHit, maskAir)
                                ? previousCell
                                : sample.asClaim(projectedHit);
                        }
                        nextCell = nextCell.withHeld(false);
                        nextProjected.put(key, nextCell);
                        if (previousCell != null) {
                            retainedClaimCount++;
                        }
                        if (deltaBaseline != null && nextCell != previousCell) {
                            changedClaimKeys.add(key);
                        }
                        if (blockEntities && !maskAir && !remoteAir) {
                            BlockEntitySample blockEntity = plateCell != null
                                ? plateCell.blockEntity()
                                : (memo.blocks().blockEntityCandidate(memo.blocks().material(sample.data))
                                    ? destView.sampleBlockEntity(rx, ry, rz)
                                    : null);
                            if (blockEntity != null) {
                                nextBlockEntities.put(key, blockEntity);
                            }
                        }
                        if (observerOcclusion && recursiveHit == null) {
                            addObserverTarget(key, remoteKey);
                            rememberOcclusionBlocker(key, nextCell, true);
                        }
                    }
                }
            }

            return true;
        }

        private boolean finishGeometry(long deadlineNanos) {
            if (finishStage == FINISH_SEAL) {
                beginFinish();
            }
            if (finishStage == FINISH_UNRESOLVED_TARGETS) {
                if (filterTargets && !filterObserverTargets(unresolvedTargetCells, unresolvedTargetRemoteKeys, deadlineNanos)) {
                    return false;
                }
                finishStage = FINISH_OBSERVER_TARGETS;
                finishTargetIndex = 0;
            }
            if (filterTargets && !filterObserverTargets(observerTargetCells, observerTargetRemoteKeys, deadlineNanos)) {
                return false;
            }
            finishEntityOcclusion();
            return true;
        }

        private void beginFinish() {
            blackoutClaims = 0;
            if (blackoutEnabled && blackoutFarSliceFound && !blackoutGeometry.isEmpty()) {
                sealBlackoutGeometry();
            }
            unfilteredClaimCount = nextProjected.size();
            filterTargets = false;
            if (observerOcclusion && (!unresolvedTargetCells.isEmpty() || !observerTargetCells.isEmpty())) {
                viewOcclusion.setRevealMarginDegrees(scannedRevealMargin);
                viewOcclusion.beginPass(
                    remoteOriginX, remoteOriginY, remoteOriginZ, projectionRemoteFrame.getNormal(),
                    occlusionGeometry);
                filterTargets = !occlusionGeometry.isEmpty();
            }
            finishStage = FINISH_UNRESOLVED_TARGETS;
            finishTargetIndex = 0;
        }

        private boolean filterObserverTargets(LongArrayList targetCells, LongArrayList targetRemoteKeys, long deadlineNanos) {
            int size = targetCells.size();
            for (; finishTargetIndex < size; finishTargetIndex++) {
                if (++finishDeadlineTargets > FINISH_DEADLINE_STRIDE) {
                    finishDeadlineTargets = 1;
                    if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos) {
                        return false;
                    }
                }
                filterObserverTarget(destView, scannedRemoteEyeX, scannedRemoteEyeY, scannedRemoteEyeZ,
                    targetCells.getLong(finishTargetIndex), targetRemoteKeys.getLong(finishTargetIndex));
            }
            return true;
        }

        private void finishEntityOcclusion() {
            if (holdClaims) {
                holdReleasedClaims();
            }
            entityOcclusion.beginPass(
                destView,
                remoteOriginX,
                remoteOriginY,
                remoteOriginZ,
                projectionRemoteFrame.getNormal(),
                observerOcclusion ? occlusionGeometry : null,
                scannedRemoteEyeX,
                scannedRemoteEyeY,
                scannedRemoteEyeZ,
                scannedRevealMargin);
            entityOcclusion.retainRevision(scannedDestinationRevision);
            if (settings.get().debug()) {
                recountProjectionChanges(forceFullSend);
            }
        }

        private void holdReleasedClaims() {
            if (retainedClaimCount != projected.size() || nextProjected.size() != unfilteredClaimCount) {
                boolean proving = holdProof != null && holdProof.beginEye(eyeX, eyeY, eyeZ);
                ObjectIterator<Long2ObjectMap.Entry<BlockClaim<B, V>>> iterator = projected.long2ObjectEntrySet().fastIterator();
                while (iterator.hasNext()) {
                    Long2ObjectMap.Entry<BlockClaim<B, V>> entry = iterator.next();
                    long key = entry.getLongKey();
                    if (nextProjected.containsKey(key)) {
                        continue;
                    }
                    BlockClaim<B, V> previous = entry.getValue();
                    if (proving && !previous.isBlackout() && holdProof.verdict(CellKeys.unpackX(key),
                        CellKeys.unpackY(key), CellKeys.unpackZ(key), this).holds()) {
                        holdClaim(key, previous);
                        coneHolds++;
                    } else if (deltaBaseline != null) {
                        removedClaimKeys.add(key);
                    }
                }
            }
            evictOverflowHeldClaims();
            removedClaimsResolved = deltaBaseline != null;
            resolvedClaimCount = nextProjected.size();
        }

        @Override
        public HoldProof.Occupancy occupancy(int x, int y, int z) {
            if (y < localMinY || y > localMaxY) {
                return HoldProof.Occupancy.OPEN;
            }
            if (!localChunkReady(localView, x, z)) {
                return HoldProof.Occupancy.UNKNOWN;
            }
            return memo.localOccupancy(localView, x, y, z);
        }

        /**
         * Every shell cell (the deepest slab plus each slab's lateral rim, where the destination was
         * transparent) becomes a claim of the blackout block, replacing whatever the scan projected
         * there. Unchanged shell claims are reused so the delta stays quiet for a stationary viewer.
         */
        private void sealBlackoutGeometry() {
            LongIterator iterator = blackoutGeometry.iterator();
            while (iterator.hasNext()) {
                long key = iterator.nextLong();
                BlockClaim<B, V> existing = nextProjected.get(key);
                long remoteKey = blackoutRemoteKeys.get(key);
                BlockClaim<B, V> previous = projected.get(key);
                BlockClaim<B, V> claim = previousShellMatches(previous, destView, remoteKey)
                    && previous.getData().equals(blackoutData)
                    ? previous
                    : BlockClaim.blackout(blackoutData, destView, remoteKey);
                nextProjected.put(key, claim);
                blackoutClaims++;
                if (existing == null && previous != null) {
                    retainedClaimCount++;
                    retainUnresolvedOcclusion(key, observerOcclusion);
                }
                if (deltaBaseline != null) {
                    if (claim != previous) {
                        changedClaimKeys.add(key);
                    } else {
                        changedClaimKeys.remove(key);
                    }
                }
            }
        }
    }

    private record CellMapping(int localFrame, int remoteFrame, int mirrorFrame,
                               double localOriginX, double localOriginY, double localOriginZ,
                               double remoteOriginX, double remoteOriginY, double remoteOriginZ,
                               boolean mirror, int mirrorRotation, double planeClearance,
                               boolean mergedSlabs, int mergeDistance, int detailCutoff) {
    }

    public static boolean shouldProjectAirSample(Sample.Kind kind, boolean localAir) {
        return (kind == Sample.Kind.MASK_AIR || kind == Sample.Kind.REMOTE_AIR) && !localAir;
    }

    public record ScanSettings(int recursiveDepth, double revealMarginDegrees, double aperturePadding, boolean debug,
                               boolean holdInvisibleClaims, int maxHeldClaims, boolean finishInSlot) {
        public ScanSettings {
            recursiveDepth = Math.clamp(recursiveDepth, 3, 64);
            revealMarginDegrees = Math.clamp(revealMarginDegrees, 0.0D, 15.0D);
            aperturePadding = Math.clamp(aperturePadding, 0.0D, 8.0D);
            maxHeldClaims = Math.clamp(maxHeldClaims, 0, 50_000_000);
        }
    }

    public record Context<B, M, W, P extends Endpoint, V extends ContentView<B, M>>(
        P portal, CellAperture aperture, Sampler<B, M, W, P, V> sampler,
        SampleMemo<B, M, V> memo, Blackout<B> blackout, Supplier<ScanSettings> settings) {
    }
}
