package art.arcane.wormholes.render;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.render.blockentity.BlockEntityMaterials;
import art.arcane.wormholes.render.blockentity.BlockEntitySample;
import art.arcane.wormholes.render.lod.LodPolicy;
import art.arcane.wormholes.render.plate.PlateCell;
import art.arcane.wormholes.render.plate.ViewPlate;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

final class ProjectorCellScan {
    private final ILocalPortal portal;
    private final ProjectorSampler sampler;
    private final ProjectorSampleMemo memo;
    private final ProjectorBlackoutSeal blackout;
    private final ProjectorViewOcclusion viewOcclusion;
    private ProjectedEntityOcclusion entityOcclusion;
    private ProjectedEntityOcclusion projectedEntityOcclusion;
    private final ProjectorBlackoutBoundary blackoutBoundary;
    private final ProjectorFrameTransform cellTransform;
    private final double[] scratchRot;
    private final double[] scratchRemotePoint;
    private final double[] scratchRemoteEye;
    private final int[] scratchAxisMin;
    private final int[] scratchAxisMax;
    private final double[] scratchAxisOrigin;
    private final double[] scratchSlabWindowBounds;
    private final double[] scratchBlackoutSlabWindowBounds;
    private final int[] scratchCellCoords;
    private final Long2ByteOpenHashMap localChunkReadiness;
    private final ProjectorEmptyCellRuns emptyCells;
    private final ProjectorFrustumRow frustumRow;
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
    private Long2ObjectOpenHashMap<ProjectedBlockClaim> projected;
    private Long2ObjectOpenHashMap<ProjectedBlockClaim> nextProjected;
    private final LongOpenHashSet changedClaimKeys;
    private final LongOpenHashSet removedClaimKeys;
    private Long2ObjectMap<ProjectedBlockClaim> deltaBaseline;
    private int retainedClaimCount;
    private int unfilteredClaimCount;
    private Long2ObjectOpenHashMap<BlockEntitySample> projectedBlockEntities;
    private Long2ObjectOpenHashMap<BlockEntitySample> nextBlockEntities;
    private PortalFrame projectionLocalFrame;
    private PortalFrame projectionRemoteFrame;
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
    private ProjectionWorldView scannedLocalView;
    private ProjectionWorldView scannedDestinationView;
    private Frustum4D scannedFrustum;
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
    private PortalFrame committedLocalFrame;
    private PortalFrame committedRemoteFrame;
    private double committedEyeDot;

    ProjectorCellScan(ILocalPortal portal,
                      ProjectorSampler sampler,
                      ProjectorSampleMemo memo,
                      ProjectorBlackoutSeal blackout) {
        this.portal = portal;
        this.sampler = sampler;
        this.memo = memo;
        this.blackout = blackout;
        this.viewOcclusion = new ProjectorViewOcclusion();
        this.entityOcclusion = new ProjectedEntityOcclusion();
        this.projectedEntityOcclusion = new ProjectedEntityOcclusion();
        this.blackoutBoundary = new ProjectorBlackoutBoundary();
        this.cellTransform = new ProjectorFrameTransform();
        this.scratchRot = new double[3];
        this.scratchRemotePoint = new double[3];
        this.scratchRemoteEye = new double[3];
        this.scratchAxisMin = new int[3];
        this.scratchAxisMax = new int[3];
        this.scratchAxisOrigin = new double[3];
        this.scratchSlabWindowBounds = new double[4];
        this.scratchBlackoutSlabWindowBounds = new double[4];
        this.scratchCellCoords = new int[3];
        this.localChunkReadiness = new Long2ByteOpenHashMap(16);
        this.emptyCells = new ProjectorEmptyCellRuns();
        this.frustumRow = new ProjectorFrustumRow();
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
        this.projected = new Long2ObjectOpenHashMap<ProjectedBlockClaim>(256);
        this.nextProjected = new Long2ObjectOpenHashMap<ProjectedBlockClaim>(256);
        this.changedClaimKeys = new LongOpenHashSet(64);
        this.removedClaimKeys = new LongOpenHashSet(64);
        this.projectedBlockEntities = new Long2ObjectOpenHashMap<BlockEntitySample>(16);
        this.nextBlockEntities = new Long2ObjectOpenHashMap<BlockEntitySample>(16);
    }

    Long2ObjectOpenHashMap<ProjectedBlockClaim> claims() {
        return preparedResult ? nextProjected : projected;
    }

    ProjectionClaimSet.ClaimDelta claimDelta() {
        if (!preparedResult) {
            throw new IllegalStateException("Projection scan is not complete");
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
        return new ProjectionClaimSet.ClaimDelta(deltaBaseline, nextProjected, changedClaimKeys, removedClaimKeys);
    }

    Long2ObjectOpenHashMap<BlockEntitySample> blockEntities() {
        return preparedResult ? nextBlockEntities : projectedBlockEntities;
    }

    boolean hasProjection() {
        return !projected.isEmpty();
    }

    /** Shell cells the last completed scan sealed with the blackout block. */
    int blackoutClaims() {
        return blackoutClaims;
    }

    ProjectedEntityOcclusion entityOcclusion() {
        return preparedResult && !reuseCommittedEntityOcclusion ? entityOcclusion : projectedEntityOcclusion;
    }

    PortalFrame localFrame() {
        return preparedResult ? projectionLocalFrame : committedLocalFrame;
    }

    PortalFrame remoteFrame() {
        return preparedResult ? projectionRemoteFrame : committedRemoteFrame;
    }

    double eyeDot() {
        return preparedResult ? projectionEyeDot : committedEyeDot;
    }

    int enterCount() {
        return enterCount;
    }

    int exitCount() {
        return exitCount;
    }

    int keptCount() {
        return keptCount;
    }

    int planeRejected() {
        return planeRejected;
    }

    int windowRejected() {
        return windowRejected;
    }

    int frustumRejected() {
        return frustumRejected;
    }

    int frustumMaskedRows() {
        return frustumMaskedRows;
    }

    int frustumScalarRows() {
        return frustumScalarRows;
    }

    int occlusionRejected() {
        return occlusionRejected;
    }

    int occlusionVoxelSteps() {
        return viewOcclusion.voxelSteps();
    }

    boolean occlusionBudgetExhausted() {
        return viewOcclusion.budgetExhausted();
    }

    int occlusionProofHits() {
        return viewOcclusion.hiddenProofHits();
    }

    int occlusionProofRevalidations() {
        return viewOcclusion.hiddenProofRevalidations();
    }

    int occlusionProofInvalidations() {
        return viewOcclusion.hiddenProofInvalidations();
    }

    int adjacentOcclusionHits() {
        return viewOcclusion.adjacentOcclusionHits();
    }

    int unresolvedOcclusionCells() {
        return nextUnresolvedOcclusion.size();
    }

    boolean hasUnresolvedOcclusion() {
        return !projectedUnresolvedOcclusion.isEmpty();
    }

    int maskedCells() {
        return maskedCells;
    }

    int plateHits() {
        return plateHits;
    }

    int emptyCellSkips() {
        return emptyCellSkips;
    }

    void invalidateContent() {
        emptyCells.clear();
    }

    void clear() {
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
        entityOcclusion.disable();
    }

    void commit() {
        if (!preparedResult) {
            throw new IllegalStateException("Projection scan is not complete");
        }
        if (!reuseCommittedEntityOcclusion) {
            LongOpenHashSet occlusionSwap = projectedOcclusionGeometry;
            projectedOcclusionGeometry = occlusionGeometry;
            occlusionGeometry = occlusionSwap;
            ProjectedEntityOcclusion entityOcclusionSwap = projectedEntityOcclusion;
            projectedEntityOcclusion = entityOcclusion;
            entityOcclusion = entityOcclusionSwap;
        }
        committedLocalFrame = projectionLocalFrame;
        committedRemoteFrame = projectionRemoteFrame;
        committedEyeDot = projectionEyeDot;
        pending = null;
        preparedResult = false;
        reuseCommittedEntityOcclusion = false;
        Long2ObjectOpenHashMap<ProjectedBlockClaim> swap = projected;
        projected = nextProjected;
        nextProjected = swap;
        Long2ObjectOpenHashMap<BlockEntitySample> blockEntitySwap = projectedBlockEntities;
        projectedBlockEntities = nextBlockEntities;
        nextBlockEntities = blockEntitySwap;
        LongOpenHashSet unresolvedSwap = projectedUnresolvedOcclusion;
        projectedUnresolvedOcclusion = nextUnresolvedOcclusion;
        nextUnresolvedOcclusion = unresolvedSwap;
        scanCommitted = true;
    }

    boolean canResumeOcclusion(ProjectorDestination destination, Location eye, Frustum4D frustum) {
        return scanCommitted && completeGeometry && hasUnresolvedOcclusion()
            && frustum == scannedFrustum
            && destination.localView == scannedLocalView && destination.destView == scannedDestinationView
            && scannedLocalRevision == destination.localView.getRevision()
            && scannedDestinationRevision == destination.destView.getRevision()
            && eye.getX() == scannedEyeX && eye.getY() == scannedEyeY && eye.getZ() == scannedEyeZ
            && scannedRevealMargin == Settings.PROJECTION_OCCLUSION_REVEAL_MARGIN_DEGREES
            && scannedBlackout == blackout.isEnabled();
    }

    void invalidateOcclusionContinuation() {
        completeGeometry = false;
    }

    void resumeOcclusion() {
        preparedResult = true;
        reuseCommittedEntityOcclusion = true;
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
        filterUnresolvedTargets(unresolvedTargetCells, unresolvedTargetRemoteKeys);
        filterUnresolvedTargets(observerTargetCells, observerTargetRemoteKeys);
        projectedEntityOcclusion.updateEye(scannedRemoteEyeX, scannedRemoteEyeY, scannedRemoteEyeZ);
        if (Settings.DEBUG) {
            recountProjectionChanges(false);
        }
    }

    void run(ProjectorDestination destination,
             PortalProjector.RtpProjectionTarget rtpTarget,
             Location eye,
             Frustum4D frustum,
             double depthBlocks,
             boolean forceStableCellResample,
             boolean forceFullSend,
             boolean refreshObserverVisibility,
             boolean buriedCellCulling,
             ProjectionRenderMode renderMode,
             ViewPlate plate,
             boolean blockEntities,
             LodPolicy lod) {
        begin(destination, rtpTarget, eye, frustum, depthBlocks, forceStableCellResample, forceFullSend,
            refreshObserverVisibility, buriedCellCulling, renderMode, plate, blockEntities, lod);
        while (!advance(Long.MAX_VALUE)) {
        }
    }

    void begin(ProjectorDestination destination,
             PortalProjector.RtpProjectionTarget rtpTarget,
             Location eye,
             Frustum4D frustum,
             double depthBlocks,
             boolean forceStableCellResample,
             boolean forceFullSend,
             boolean refreshObserverVisibility,
             boolean buriedCellCulling,
             ProjectionRenderMode renderMode,
             ViewPlate plate,
             boolean blockEntities,
             LodPolicy lod) {
        if (pending != null) {
            cancelPending();
        }
        preparedResult = false;
        reuseCommittedEntityOcclusion = false;
        pending = new ScanPass(new ScanRequest(destination, rtpTarget, eye, frustum, depthBlocks,
            forceStableCellResample, forceFullSend, refreshObserverVisibility, buriedCellCulling,
            renderMode, plate, blockEntities, lod));
    }

    boolean advance(long deadlineNanos) {
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
            if (deadlineNanos != Long.MAX_VALUE) {
                return false;
            }
        }
        pass.finishGeometry();
        pass.ready = true;
        preparedResult = true;
        return true;
    }

    boolean hasPending() {
        return pending != null;
    }

    void cancelPending() {
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
        entityOcclusion.disable();
    }

    static boolean scanContinues(int coordinate, int end, int step) {
        return step > 0 ? coordinate <= end : coordinate >= end;
    }

    private static int frameCode(PortalFrame frame) {
        return frame.getNormal().ordinal() | (frame.getRight().ordinal() << 3) | (frame.getUp().ordinal() << 6);
    }

    private boolean recursiveGeometryIntersects(ProjectorRecursivePortals.Index index, AxisAlignedBB area) {
        if (index == null || index.isEmpty()) {
            return false;
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            cellTransform.apply((corner & 1) == 0 ? area.getXa() - 1.0D : area.getXb() + 1.0D,
                (corner & 2) == 0 ? area.getYa() - 1.0D : area.getYb() + 1.0D,
                (corner & 4) == 0 ? area.getZa() - 1.0D : area.getZb() + 1.0D, scratchRemotePoint);
            minX = Math.min(minX, scratchRemotePoint[0]);
            minY = Math.min(minY, scratchRemotePoint[1]);
            minZ = Math.min(minZ, scratchRemotePoint[2]);
            maxX = Math.max(maxX, scratchRemotePoint[0]);
            maxY = Math.max(maxY, scratchRemotePoint[1]);
            maxZ = Math.max(maxZ, scratchRemotePoint[2]);
        }
        return index.intersects(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private boolean localChunkReady(ProjectionWorldView view, int x, int z) {
        long key = ((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
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

    void updateEntityOcclusionEye(Location eye,
                                  ProjectorDestination destination,
                                  PortalFrame localViewFrame,
                                  PortalFrame remoteViewFrame) {
        double localOriginX = portal.getOrigin().getX();
        double localOriginY = portal.getOrigin().getY();
        double localOriginZ = portal.getOrigin().getZ();
        if (destination.mirrorMode) {
            PortalCoordMap.mirrorDisplayToSourcePointInto(
                eye.getX(), eye.getY(), eye.getZ(),
                localOriginX, localOriginY, localOriginZ,
                portal.getFrame(), destination.mirrorRotationQuarterTurns, scratchRemoteEye);
        } else {
            localViewFrame.transformPointInto(
                eye.getX(), eye.getY(), eye.getZ(),
                localOriginX, localOriginY, localOriginZ,
                destination.originX, destination.originY, destination.originZ,
                remoteViewFrame, scratchRemoteEye);
        }
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

    private void rememberOcclusionBlocker(ProjectedBlockClaim claim, boolean observerOcclusion) {
        if (!observerOcclusion
            || claim.getLightRemoteKey() == ProjectedBlockClaim.NO_REMOTE_KEY
            || !viewOcclusion.isOccluding(claim.getData())) {
            return;
        }
        occlusionGeometry.add(claim.getLightRemoteKey());
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
            mask |= ProjectorBlackoutBoundary.faceMask(rightAxis, -1);
        }
        if (right == rightMaximum) {
            mask |= ProjectorBlackoutBoundary.faceMask(rightAxis, 1);
        }
        if (up == upMinimum) {
            mask |= ProjectorBlackoutBoundary.faceMask(upAxis, -1);
        }
        if (up == upMaximum) {
            mask |= ProjectorBlackoutBoundary.faceMask(upAxis, 1);
        }
        return mask;
    }

    private void rememberBlackoutCell(long key,
                                      long remoteKey,
                                      int boundaryMask,
                                      ProjectedBlockClaim claim) {
        if (!viewOcclusion.isOccluding(claim.getData())) {
            addBlackoutCell(key, remoteKey, boundaryMask);
        }
    }

    private void rememberBlackoutCell(long key,
                                      long remoteKey,
                                      int boundaryMask,
                                      ProjectorSample sample) {
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
    private static boolean previousShellMatches(ProjectedBlockClaim previous, ProjectionWorldView destView, long remoteKey) {
        return previous != null
            && previous.isBlackout()
            && previous.getLightRemoteKey() == remoteKey
            && previous.getLightView() == destView;
    }

    private void filterObserverTargets(ProjectionWorldView view,
                                       double eyeX,
                                       double eyeY,
                                       double eyeZ,
                                       LongArrayList targetCells,
                                       LongArrayList targetRemoteKeys) {
        for (int index = 0; index < targetCells.size(); index++) {
            long localKey = targetCells.getLong(index);
            long remoteKey = targetRemoteKeys.getLong(index);
            filterObserverTarget(view, eyeX, eyeY, eyeZ, localKey, remoteKey);
        }
    }

    private void filterUnresolvedTargets(LongArrayList targetCells, LongArrayList targetRemoteKeys) {
        for (int index = 0; index < targetCells.size(); index++) {
            long localKey = targetCells.getLong(index);
            if (projectedUnresolvedOcclusion.contains(localKey)) {
                filterObserverTarget(scannedDestinationView,
                    scannedRemoteEyeX, scannedRemoteEyeY, scannedRemoteEyeZ,
                    localKey, targetRemoteKeys.getLong(index));
            }
        }
    }

    private void filterObserverTarget(ProjectionWorldView view, double eyeX, double eyeY, double eyeZ,
                                      long localKey, long remoteKey) {
        ProjectorViewOcclusion.Visibility visibility = viewOcclusion.visibility(view,
            ProjectionCellKey.unpackX(remoteKey), ProjectionCellKey.unpackY(remoteKey),
            ProjectionCellKey.unpackZ(remoteKey), eyeX, eyeY, eyeZ);
        switch (visibility) {
            case HIDDEN -> {
                ProjectedBlockClaim hidden = nextProjected.get(localKey);
                if (hidden == null || !hidden.isBlackout()) {
                    nextProjected.remove(localKey);
                    occlusionRejected++;
                }
            }
            case UNRESOLVED -> nextUnresolvedOcclusion.add(localKey);
            case VISIBLE -> {
            }
        }
    }

    private void recountProjectionChanges(boolean forceFullSend) {
        enterCount = 0;
        keptCount = 0;
        maskedCells = 0;
        int retainedKeys = 0;
        ObjectIterator<Long2ObjectMap.Entry<ProjectedBlockClaim>> iterator = nextProjected.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            Long2ObjectMap.Entry<ProjectedBlockClaim> entry = iterator.next();
            ProjectedBlockClaim nextCell = entry.getValue();
            ProjectedBlockClaim previousCell = projected.get(entry.getLongKey());
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

    private record ScanRequest(ProjectorDestination destination,
                               PortalProjector.RtpProjectionTarget rtpTarget,
                               Location eye,
                               Frustum4D frustum,
                               double depthBlocks,
                               boolean forceStableCellResample,
                               boolean forceFullSend,
                               boolean refreshObserverVisibility,
                               boolean buriedCellCulling,
                               ProjectionRenderMode renderMode,
                               ViewPlate plate,
                               boolean blockEntities,
                               LodPolicy lod) {
    }

    private final class ScanPass {
        private ProjectionWorldView localView;
        private ProjectionWorldView destView;
        private ILocalPortal dest;
        private Frustum4D frustum;
        private ViewPlate plate;
        private PortalFrame localFrame;
        private ProjectorRecursivePortals.Index rootRecursiveIndex;
        private ProjectorPlaneWindow planeWindow;
        private ProjectorPlaneWindow blackoutWindow;
        private LodPolicy lodPolicy;
        private int[] axisMin;
        private int[] axisMax;
        private int[] cellCoords;
        private double[] axisOrigin;
        private double[] slabWindowBounds;
        private double[] blackoutSlabWindowBounds;
        private boolean mirrorMode;
        private boolean forceStableCellResample;
        private boolean forceFullSend;
        private boolean refreshObserverVisibility;
        private boolean buriedCellCulling;
        private boolean blockEntities;
        private boolean eyeFrontSide;
        private boolean recursiveGeometry;
        private boolean cacheEmptyCells;
        private boolean skipKnownEmptyCells;
        private boolean blackoutEnabled;
        private BlockData blackoutData;
        private boolean observerOcclusion;
        private boolean blackoutFarSliceFound;
        private boolean lodActive;
        private boolean reuseMappedClaims;
        private boolean blackoutSlab;
        private boolean mergedSlab;
        private boolean windowContainsRow;
        private boolean frustumContainsRow;
        private boolean frustumRowPrepared;
        private boolean blackoutContainsRow;
        private int mirrorRotationQuarterTurns;
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
        private int blackoutRightBlockMin;
        private int blackoutRightBlockMax;
        private int blackoutUpBlockMin;
        private int blackoutUpBlockMax;
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
        private double portalPlaneClearance;
        private double maxProjectionDepth;
        private double projectionFacingNormal;
        private double localFacingNormal;
        private double slabSignedDistance;
        private double cellDot;
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

        private ScanPass(ScanRequest request) {
            ProjectorDestination destination = request.destination();
            PortalProjector.RtpProjectionTarget rtpTarget = request.rtpTarget();
            Location eye = request.eye();
            frustum = request.frustum();
            double depthBlocks = request.depthBlocks();
            forceStableCellResample = request.forceStableCellResample();
            forceFullSend = request.forceFullSend();
            refreshObserverVisibility = request.refreshObserverVisibility();
            buriedCellCulling = request.buriedCellCulling();
            ProjectionRenderMode renderMode = request.renderMode();
            plate = request.plate();
            blockEntities = request.blockEntities();
            LodPolicy lod = request.lod();
            recursiveDepth = Settings.PROJECTION_RECURSIVE_PORTAL_DEPTH;
            blackoutData = blackout.data();
            localView = destination.localView;
            destView = destination.destView;
            dest = destination.dest;
            mirrorMode = destination.mirrorMode;
            mirrorRotationQuarterTurns = destination.mirrorRotationQuarterTurns;

            boolean reuseCommittedContent = scanCommitted && completeGeometry
                && !forceStableCellResample && !forceFullSend
                && localView == scannedLocalView && destView == scannedDestinationView
                && localView.getRevision() == scannedLocalRevision
                && destView.getRevision() == scannedDestinationRevision;
            deltaBaseline = scanCommitted && !forceFullSend ? projected : null;
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
            scannedRevealMargin = Settings.PROJECTION_OCCLUSION_REVEAL_MARGIN_DEGREES;
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

            int localMinY = localView.getMinHeight();
            int localMaxY = localView.getMaxHeight() - 1;
            AxisAlignedBB area = frustum.getRegion();
            int xa = PortalProjector.minBlockForCenter(area.getXa());
            int ya = Math.max(PortalProjector.minBlockForCenter(area.getYa()), localMinY);
            int za = PortalProjector.minBlockForCenter(area.getZa());
            int xb = PortalProjector.maxBlockForCenter(area.getXb());
            int yb = Math.min(PortalProjector.maxBlockForCenter(area.getYb()), localMaxY);
            int zb = PortalProjector.maxBlockForCenter(area.getZb());

            localFrame = portal.getFrame();
            PortalFrame remoteFrame = rtpTarget != null
                ? rtpTarget.frame()
                : mirrorMode ? localFrame.flipNormal() : destination.destAnchor.getFrame();
            double localOriginX = portal.getOrigin().getX();
            double localOriginY = portal.getOrigin().getY();
            double localOriginZ = portal.getOrigin().getZ();
            remoteOriginX = mirrorMode ? localOriginX : destination.originX;
            remoteOriginY = mirrorMode ? localOriginY : destination.originY;
            remoteOriginZ = mirrorMode ? localOriginZ : destination.originZ;

            double facingX = localFrame.getNormal().x();
            double facingY = localFrame.getNormal().y();
            double facingZ = localFrame.getNormal().z();
            eyeX = eye.getX();
            eyeY = eye.getY();
            eyeZ = eye.getZ();
            double eyeRelX = eyeX - localOriginX;
            double eyeRelY = eyeY - localOriginY;
            double eyeRelZ = eyeZ - localOriginZ;
            eyeFrontSide = (eyeRelX * facingX + eyeRelY * facingY + eyeRelZ * facingZ) >= 0.0D;
            projectionLocalFrame = PortalProjector.viewFrame(localFrame, eyeFrontSide);
            projectionRemoteFrame = PortalProjector.viewFrame(remoteFrame, eyeFrontSide);
            if (mirrorMode) {
                PortalCoordMap.mirrorDisplayToSourcePointInto(eyeX, eyeY, eyeZ,
                    localOriginX, localOriginY, localOriginZ, localFrame, mirrorRotationQuarterTurns, scratchRemoteEye);
                cellTransform.configureMirror(localFrame, mirrorRotationQuarterTurns,
                    localOriginX, localOriginY, localOriginZ, scratchRot);
            } else {
                projectionLocalFrame.transformPointInto(eyeX, eyeY, eyeZ,
                    localOriginX, localOriginY, localOriginZ,
                    remoteOriginX, remoteOriginY, remoteOriginZ,
                    projectionRemoteFrame, scratchRemoteEye);
                cellTransform.configure(projectionLocalFrame, projectionRemoteFrame,
                    localOriginX, localOriginY, localOriginZ,
                    remoteOriginX, remoteOriginY, remoteOriginZ);
            }
            sampler.prepareTransformCache(projectionRemoteFrame, projectionLocalFrame, mirrorMode, mirrorRotationQuarterTurns);
            scannedRemoteEyeX = scratchRemoteEye[0];
            scannedRemoteEyeY = scratchRemoteEye[1];
            scannedRemoteEyeZ = scratchRemoteEye[2];
            World destSampleWorld = destView.getWorld();
            rootRecursiveIndex = destSampleWorld == null || recursiveDepth < 0
                ? null
                : sampler.recursiveIndex(destSampleWorld, scratchRemoteEye[0], scratchRemoteEye[1], scratchRemoteEye[2], dest);
            recursiveGeometry = recursiveGeometryIntersects(rootRecursiveIndex, area);
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
            portalPlaneClearance = PortalProjector.portalPlaneClearance(portal.getStructure().getArea(), localFrame);
            maxProjectionDepth = depthBlocks + portalPlaneClearance;
            double signedMinDistance = eyeFrontSide ? -maxProjectionDepth : portalPlaneClearance;
            double signedMaxDistance = eyeFrontSide ? -portalPlaneClearance : maxProjectionDepth;
            planeWindow = ProjectorPlaneWindow.create(portal.getStructure(), portal.getStructure().getArea(), projectionLocalFrame,
                localOriginX, localOriginY, localOriginZ, Settings.PROJECTION_APERTURE_PADDING_BLOCKS,
                projectionEyeDot);
            blackoutWindow = blackoutEnabled
                ? ProjectorPlaneWindow.create(portal.getStructure(), portal.getStructure().getArea(), projectionLocalFrame,
                    localOriginX, localOriginY, localOriginZ, 0.0D, projectionEyeDot)
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
                double centerA = localOriginX + (signedMinDistance / facingX);
                double centerB = localOriginX + (signedMaxDistance / facingX);
                xa = Math.max(xa, PortalProjector.minBlockForCenter(Math.min(centerA, centerB)));
                xb = Math.min(xb, PortalProjector.maxBlockForCenter(Math.max(centerA, centerB)));
            } else if (facingY != 0.0D) {
                double centerA = localOriginY + (signedMinDistance / facingY);
                double centerB = localOriginY + (signedMaxDistance / facingY);
                ya = Math.max(ya, PortalProjector.minBlockForCenter(Math.min(centerA, centerB)));
                yb = Math.min(yb, PortalProjector.maxBlockForCenter(Math.max(centerA, centerB)));
            } else {
                double centerA = localOriginZ + (signedMinDistance / facingZ);
                double centerB = localOriginZ + (signedMaxDistance / facingZ);
                za = Math.max(za, PortalProjector.minBlockForCenter(Math.min(centerA, centerB)));
                zb = Math.min(zb, PortalProjector.maxBlockForCenter(Math.max(centerA, centerB)));
            }

            Direction projectionNormalDirection = projectionLocalFrame.getNormal();
            Direction projectionRightDirection = projectionLocalFrame.getRight();
            Direction projectionUpDirection = projectionLocalFrame.getUp();
            normalAxis = projectionNormalDirection.x() != 0 ? 0 : (projectionNormalDirection.y() != 0 ? 1 : 2);
            blackoutFarSign = -(projectionNormalDirection.x()
                + projectionNormalDirection.y() + projectionNormalDirection.z());
            rightAxis = projectionRightDirection.x() != 0 ? 0 : (projectionRightDirection.y() != 0 ? 1 : 2);
            rightSign = projectionRightDirection.x() + projectionRightDirection.y() + projectionRightDirection.z();
            upAxis = projectionUpDirection.x() != 0 ? 0 : (projectionUpDirection.y() != 0 ? 1 : 2);
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
            blackoutSlabWindowBounds = scratchBlackoutSlabWindowBounds;
            cellCoords = scratchCellCoords;
            observerOcclusion = renderMode.usesObserverOcclusion();
            localFacingNormal = normalAxis == 0 ? facingX : normalAxis == 1 ? facingY : facingZ;
            normalStep = projectionFacingNormal > 0.0D ? -1 : 1;
            normalStart = normalStep > 0 ? axisMin[normalAxis] : axisMax[normalAxis];
            normalEnd = normalStep > 0 ? axisMax[normalAxis] : axisMin[normalAxis];
            blackoutFarCoordinate = 0;
            blackoutFarSliceFound = false;
            lodPolicy = lod == null ? LodPolicy.NONE : lod;
            lodActive = !lodPolicy.isNone();
            CellMapping mapping = new CellMapping(frameCode(projectionLocalFrame), frameCode(projectionRemoteFrame), frameCode(localFrame),
                localOriginX, localOriginY, localOriginZ, remoteOriginX, remoteOriginY, remoteOriginZ,
                mirrorMode, mirrorRotationQuarterTurns, portalPlaneClearance,
                lodPolicy.mergeRuns(), lodPolicy.distanceBlocks(), lodPolicy.detailCutoffBlocks());
            reuseMappedClaims = reuseCommittedContent && !recursiveGeometry && mapping.equals(scannedMapping);
            scannedMapping = mapping;

            n = normalStart;
        }

        private boolean advanceGeometry(long deadlineNanos) {
            for (; scanContinues(n, normalEnd, normalStep); n += normalStep, slabReady = false, rowReady = false) {
                if (!slabReady) {
                    slabSignedDistance = projectionFacingNormal * ((n + 0.5D) - axisOrigin[normalAxis]);
                    if (!planeWindow.slabWindow(eyeX, eyeY, eyeZ, slabSignedDistance, slabWindowBounds)) {
                        continue;
                    }
                    rightBlockMin = ProjectorPlaneWindow.slabBlockMin(slabWindowBounds[0], slabWindowBounds[1], rightSign, axisOrigin[rightAxis], axisMin[rightAxis]);
                    rightBlockMax = ProjectorPlaneWindow.slabBlockMax(slabWindowBounds[0], slabWindowBounds[1], rightSign, axisOrigin[rightAxis], axisMax[rightAxis]);
                    upBlockMin = ProjectorPlaneWindow.slabBlockMin(slabWindowBounds[2], slabWindowBounds[3], upSign, axisOrigin[upAxis], axisMin[upAxis]);
                    upBlockMax = ProjectorPlaneWindow.slabBlockMax(slabWindowBounds[2], slabWindowBounds[3], upSign, axisOrigin[upAxis], axisMax[upAxis]);
                    blackoutSlab = blackoutEnabled
                        && blackoutWindow.slabWindow(eyeX, eyeY, eyeZ, slabSignedDistance, blackoutSlabWindowBounds);
                    blackoutRightBlockMin = blackoutSlab
                        ? ProjectorPlaneWindow.slabBlockMin(
                            blackoutSlabWindowBounds[0], blackoutSlabWindowBounds[1], rightSign,
                            axisOrigin[rightAxis], axisMin[rightAxis])
                        : 0;
                    blackoutRightBlockMax = blackoutSlab
                        ? ProjectorPlaneWindow.slabBlockMax(
                            blackoutSlabWindowBounds[0], blackoutSlabWindowBounds[1], rightSign,
                            axisOrigin[rightAxis], axisMax[rightAxis])
                        : -1;
                    blackoutUpBlockMin = blackoutSlab
                        ? ProjectorPlaneWindow.slabBlockMin(
                            blackoutSlabWindowBounds[2], blackoutSlabWindowBounds[3], upSign,
                            axisOrigin[upAxis], axisMin[upAxis])
                        : 0;
                    blackoutUpBlockMax = blackoutSlab
                        ? ProjectorPlaneWindow.slabBlockMax(
                            blackoutSlabWindowBounds[2], blackoutSlabWindowBounds[3], upSign,
                            axisOrigin[upAxis], axisMax[upAxis])
                        : -1;
                    cellDot = localFacingNormal * ((n + 0.5D) - axisOrigin[normalAxis]);
                    if (!PortalProjector.projectsBehindPortalPlane(cellDot, eyeFrontSide, portalPlaneClearance)
                        || Math.abs(cellDot) > maxProjectionDepth) {
                        planeRejected = addRejectedCells(
                            planeRejected, rightBlockMin, rightBlockMax, upBlockMin, upBlockMax);
                        continue;
                    }
                    slabIndex = LodPolicy.depthIndex(cellDot, portalPlaneClearance);
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
                        blackoutContainsRow = blackoutSlab
                            && blackoutWindow.containsRow(upAxis, eyeX, eyeY, eyeZ,
                                rowX, rowY, rowZ, rowEnd, slabSignedDistance);
                        if (cacheEmptyCells) {
                            emptyCells.beginRow(upAxis, cellCoords);
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

                        long key = ProjectionCellKey.pack(x, y, z);
                        ProjectedBlockClaim previousCell = projected.get(key);
                        boolean blackoutCell = blackoutSlab
                            && (blackoutContainsRow || blackoutWindow.containsRayIntersection(
                                eyeX, eyeY, eyeZ, cx, cy, cz, slabSignedDistance));
                        int blackoutBoundaryMask = 0;
                        if (blackoutCell && (!blackoutFarSliceFound || blackoutFarCoordinate != n)) {
                            clearBlackoutFarFace(normalAxis, blackoutFarSign);
                            blackoutFarCoordinate = n;
                            blackoutFarSliceFound = true;
                        }
                        if (blackoutCell) {
                            blackoutBoundaryMask = lateralBlackoutBoundaryMask(
                                r, u,
                                blackoutRightBlockMin, blackoutRightBlockMax,
                                blackoutUpBlockMin, blackoutUpBlockMax,
                                rightAxis, upAxis);
                            blackoutBoundaryMask |= ProjectorBlackoutBoundary.faceMask(normalAxis, blackoutFarSign);
                        }
                        if (reuseMappedClaims && previousCell != null && !previousCell.isBlackout()
                            && previousCell.getLightView() == destView
                            && previousCell.isFullBright() == blackoutEnabled) {
                            long remoteKey = previousCell.getLightRemoteKey();
                            nextProjected.put(key, previousCell);
                            retainedClaimCount++;
                            retainBlockEntity(key);
                            rememberOcclusionBlocker(previousCell, observerOcclusion);
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
                        if (mergedSlab) {
                            cellTransform.apply(normalAxis == 0 ? sampleNormalCenter : cx,
                                normalAxis == 1 ? sampleNormalCenter : cy,
                                normalAxis == 2 ? sampleNormalCenter : cz, scratchRemotePoint);
                        } else {
                            cellTransform.apply(cx, cy, cz, scratchRemotePoint);
                        }

                        int rx = (int) Math.floor(scratchRemotePoint[0]);
                        int ry = (int) Math.floor(scratchRemotePoint[1]);
                        int rz = (int) Math.floor(scratchRemotePoint[2]);
                        long remoteKey = ProjectionCellKey.pack(rx, ry, rz);
                        long previousRemoteKey = previousCell == null
                            ? ProjectedBlockClaim.NO_REMOTE_KEY
                            : previousCell.getLightRemoteKey();
                        if (!localChunkReady(localView, x, z)) {
                            completeGeometry = false;
                            if (previousCell != null && !previousCell.isBlackout()) {
                                ProjectedBlockClaim retained = previousCell.withFullBright(blackoutEnabled);
                                nextProjected.put(key, retained);
                                retainedClaimCount++;
                                if (deltaBaseline != null && retained != previousCell) {
                                    changedClaimKeys.add(key);
                                }
                                retainBlockEntity(key);
                                rememberOcclusionBlocker(retained, observerOcclusion);
                                retainUnresolvedOcclusion(key, observerOcclusion);
                                if (blackoutCell) {
                                    rememberBlackoutCell(key, remoteKey, blackoutBoundaryMask, retained);
                                }
                            } else if (blackoutCell && previousShellMatches(previousCell, destView, remoteKey)) {
                                retainShellClaim(key, remoteKey, blackoutBoundaryMask, previousCell);
                            }
                            continue;
                        }
                        boolean previousLightingMatches = previousCell != null
                            && !previousCell.isBlackout()
                            && previousCell.isFullBright() == blackoutEnabled;
                        if (previousLightingMatches && previousRemoteKey == remoteKey
                            && previousCell.getLightView() == destView) {
                            if (!forceStableCellResample && !forceFullSend
                                && (!refreshObserverVisibility || !recursiveGeometry)) {
                                nextProjected.put(key, previousCell);
                                retainedClaimCount++;
                                retainBlockEntity(key);
                                rememberOcclusionBlocker(previousCell, observerOcclusion);
                                if (observerOcclusion && (refreshObserverVisibility || projectedUnresolvedOcclusion.contains(key))) {
                                    addObserverTarget(key, remoteKey);
                                }
                                if (blackoutCell) {
                                    rememberBlackoutCell(key, remoteKey, blackoutBoundaryMask, previousCell);
                                }
                                continue;
                            }
                        }

                        ProjectorRecursivePortals.Hit recursiveHit = !recursiveGeometry
                            ? null
                            : rootRecursiveIndex.find(scratchRemotePoint[0], scratchRemotePoint[1], scratchRemotePoint[2],
                                recursiveDepth);
                        PlateCell plateCell = plate == null || recursiveHit != null ? null : plate.cell(key);
                        ProjectorSample sample;
                        if (plateCell != null) {
                            plateHits++;
                            sample = plateCell.sample(destView);
                        } else {
                            sample = sampler.resolve(destView,
                                scratchRemotePoint[0], scratchRemotePoint[1], scratchRemotePoint[2],
                                scannedRemoteEyeX, scannedRemoteEyeY, scannedRemoteEyeZ,
                                dest,
                                recursiveDepth,
                                buriedCellCulling,
                                rootRecursiveIndex,
                                recursiveHit);
                            if (lodActive && recursiveHit == null && sample.kind == ProjectorSample.Kind.BLOCK
                                && lodPolicy.dropsDetail(mergedSlab ? slabIndex - 1 : slabIndex, sample.data.getMaterial())) {
                                sample = new ProjectorSample(ProjectorSample.Kind.REMOTE_AIR, sampler.air(), destView, sample.remoteKey());
                            }
                        }
                        if (sample.kind == ProjectorSample.Kind.OCCLUDED) {
                            if (cacheEmptyCells) {
                                emptyCells.markEmpty(u);
                            }
                            continue;
                        }
                        if (sample.kind == ProjectorSample.Kind.NO_SAMPLE) {
                            completeGeometry = false;
                            boolean matchingRemoteUnavailable = !destView.isChunkReady(rx, rz)
                                && previousCell != null
                                && previousRemoteKey == remoteKey;
                            if (matchingRemoteUnavailable && !previousCell.isBlackout()) {
                                ProjectedBlockClaim retained = previousCell.withFullBright(blackoutEnabled);
                                nextProjected.put(key, retained);
                                retainedClaimCount++;
                                if (deltaBaseline != null && retained != previousCell) {
                                    changedClaimKeys.add(key);
                                }
                                retainBlockEntity(key);
                                rememberOcclusionBlocker(retained, observerOcclusion);
                                retainUnresolvedOcclusion(key, observerOcclusion);
                                if (blackoutCell) {
                                    rememberBlackoutCell(key, remoteKey, blackoutBoundaryMask, retained);
                                }
                            } else if (blackoutCell && previousShellMatches(previousCell, destView, remoteKey)) {
                                retainShellClaim(key, remoteKey, blackoutBoundaryMask, previousCell);
                            }
                            continue;
                        }
                        if (blackoutCell) {
                            rememberBlackoutCell(key, remoteKey, blackoutBoundaryMask, sample);
                        }
                        boolean maskAir = sample.kind == ProjectorSample.Kind.MASK_AIR;
                        boolean remoteAir = sample.kind == ProjectorSample.Kind.REMOTE_AIR;
                        boolean localAir = remoteAir && memo.isLocalAir(localView, x, y, z);
                        if ((maskAir || remoteAir) && !PortalProjector.shouldProjectAirSample(sample.kind, localAir)) {
                            if (cacheEmptyCells) {
                                emptyCells.markEmpty(u);
                            }
                            continue;
                        }
                        BlockData projectedHit;
                        if (maskAir || remoteAir) {
                            projectedHit = sampler.air();
                        } else if (plateCell != null) {
                            projectedHit = plateCell.data();
                        } else {
                            projectedHit = sampler.transformProjectedBlockData(sample.data, projectionRemoteFrame, projectionLocalFrame,
                                mirrorMode, localFrame, mirrorRotationQuarterTurns);
                        }

                        ProjectedBlockClaim nextCell;
                        if (blackoutEnabled) {
                            ProjectedBlockClaim.LightingPolicy lightingPolicy = ProjectedBlockClaim.LightingPolicy.FULL_BRIGHT;
                            nextCell = sample.matchesClaim(previousCell, projectedHit, maskAir, lightingPolicy)
                                ? previousCell
                                : sample.asClaim(projectedHit, lightingPolicy);
                        } else {
                            nextCell = sample.matchesClaim(previousCell, projectedHit, maskAir)
                                ? previousCell
                                : sample.asClaim(projectedHit);
                        }
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
                                : (BlockEntityMaterials.isCandidate(sample.data.getMaterial())
                                    ? destView.sampleBlockEntity(rx, ry, rz)
                                    : null);
                            if (blockEntity != null) {
                                nextBlockEntities.put(key, blockEntity);
                            }
                        }
                        if (observerOcclusion && recursiveHit == null) {
                            addObserverTarget(key, remoteKey);
                            rememberOcclusionBlocker(nextCell, true);
                        }
                    }
                }
            }

            return true;
        }

        private void finishGeometry() {
            blackoutClaims = 0;
            if (blackoutEnabled && blackoutFarSliceFound && !blackoutGeometry.isEmpty()) {
                sealBlackoutGeometry();
            }
            unfilteredClaimCount = nextProjected.size();
            if (observerOcclusion && (!unresolvedTargetCells.isEmpty() || !observerTargetCells.isEmpty())) {
                viewOcclusion.setRevealMarginDegrees(scannedRevealMargin);
                viewOcclusion.beginPass(
                    remoteOriginX, remoteOriginY, remoteOriginZ, projectionRemoteFrame.getNormal(),
                    occlusionGeometry);
                if (!occlusionGeometry.isEmpty()) {
                    filterObserverTargets(destView, scannedRemoteEyeX, scannedRemoteEyeY, scannedRemoteEyeZ,
                        unresolvedTargetCells, unresolvedTargetRemoteKeys);
                    filterObserverTargets(destView, scannedRemoteEyeX, scannedRemoteEyeY, scannedRemoteEyeZ,
                        observerTargetCells, observerTargetRemoteKeys);
                }
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
            if (Settings.DEBUG) {
                recountProjectionChanges(forceFullSend);
            }
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
                ProjectedBlockClaim existing = nextProjected.get(key);
                if (existing != null && existing.isBlackout() && existing.getData().equals(blackoutData)) {
                    blackoutClaims++;
                    continue;
                }
                long remoteKey = blackoutRemoteKeys.get(key);
                ProjectedBlockClaim previous = projected.get(key);
                ProjectedBlockClaim claim = previousShellMatches(previous, destView, remoteKey)
                    && previous.getData().equals(blackoutData)
                    ? previous
                    : ProjectedBlockClaim.blackout(blackoutData, destView, remoteKey);
                nextProjected.put(key, claim);
                blackoutClaims++;
                if (existing == null && previous != null) {
                    retainedClaimCount++;
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

        /** Keeps a committed shell claim over a cell whose chunks are still loading. */
        private void retainShellClaim(long key, long remoteKey, int boundaryMask, ProjectedBlockClaim previousCell) {
            nextProjected.put(key, previousCell);
            retainedClaimCount++;
            retainUnresolvedOcclusion(key, observerOcclusion);
            addBlackoutCell(key, remoteKey, boundaryMask);
        }
    }

    private record CellMapping(int localFrame, int remoteFrame, int mirrorFrame,
                               double localOriginX, double localOriginY, double localOriginZ,
                               double remoteOriginX, double remoteOriginY, double remoteOriginZ,
                               boolean mirror, int mirrorRotation, double planeClearance,
                               boolean mergedSlabs, int mergeDistance, int detailCutoff) {
    }
}
