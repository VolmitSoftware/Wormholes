package art.arcane.wormholes.render;

import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

final class ProjectorCellScan {
    private final ILocalPortal portal;
    private final ProjectorSampler sampler;
    private final ProjectorSampleMemo memo;
    private final ProjectorBlackoutSeal blackout;
    private final ProjectorViewOcclusion viewOcclusion;
    private final ProjectedEntityOcclusion entityOcclusion;
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
    private LongOpenHashSet projectedBlackoutGeometry;
    private LongOpenHashSet blackoutGeometry;
    private Long2LongOpenHashMap projectedBlackoutRemoteKeys;
    private Long2LongOpenHashMap blackoutRemoteKeys;
    private final LongOpenHashSet occlusionGeometry;
    private final LongArrayList observerTargetCells;
    private final LongArrayList observerTargetRemoteKeys;
    private final LongArrayList unresolvedTargetCells;
    private final LongArrayList unresolvedTargetRemoteKeys;
    private LongOpenHashSet projectedUnresolvedOcclusion;
    private LongOpenHashSet nextUnresolvedOcclusion;
    private Long2ObjectOpenHashMap<ProjectedBlockClaim> projected;
    private Long2ObjectOpenHashMap<ProjectedBlockClaim> nextProjected;
    private ProjectorBlackoutMesh.Result blackoutMesh;
    private ProjectionWorldView projectedBlackoutView;
    private ProjectionWorldView blackoutView;
    private PortalFrame projectionLocalFrame;
    private PortalFrame projectionRemoteFrame;
    private double projectionEyeDot;
    private int enterCount;
    private int exitCount;
    private int keptCount;
    private int planeRejected;
    private int windowRejected;
    private int frustumRejected;
    private int occlusionRejected;
    private int maskedCells;

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
        this.projectedBlackoutGeometry = new LongOpenHashSet(256);
        this.blackoutGeometry = new LongOpenHashSet(256);
        this.projectedBlackoutRemoteKeys = new Long2LongOpenHashMap(256);
        this.blackoutRemoteKeys = new Long2LongOpenHashMap(256);
        this.occlusionGeometry = new LongOpenHashSet(256);
        this.observerTargetCells = new LongArrayList(256);
        this.observerTargetRemoteKeys = new LongArrayList(256);
        this.unresolvedTargetCells = new LongArrayList(256);
        this.unresolvedTargetRemoteKeys = new LongArrayList(256);
        this.projectedUnresolvedOcclusion = new LongOpenHashSet(256);
        this.nextUnresolvedOcclusion = new LongOpenHashSet(256);
        this.projected = new Long2ObjectOpenHashMap<ProjectedBlockClaim>(256);
        this.nextProjected = new Long2ObjectOpenHashMap<ProjectedBlockClaim>(256);
        this.blackoutMesh = ProjectorBlackoutMesh.empty();
    }

    Long2ObjectOpenHashMap<ProjectedBlockClaim> claims() {
        return nextProjected;
    }

    boolean hasProjection() {
        return !projected.isEmpty() || blackoutMesh.hasProjection();
    }

    ProjectorBlackoutMesh.Result blackoutMesh() {
        return blackoutMesh;
    }

    BlockData blackoutData() {
        return blackout.data();
    }

    ProjectedEntityOcclusion entityOcclusion() {
        return entityOcclusion;
    }

    PortalFrame localFrame() {
        return projectionLocalFrame;
    }

    PortalFrame remoteFrame() {
        return projectionRemoteFrame;
    }

    double eyeDot() {
        return projectionEyeDot;
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

    void clear() {
        projected.clear();
        nextProjected.clear();
        projectedBlackoutGeometry.clear();
        blackoutGeometry.clear();
        blackoutBoundary.clear();
        projectedBlackoutRemoteKeys.clear();
        blackoutRemoteKeys.clear();
        occlusionGeometry.clear();
        observerTargetCells.clear();
        observerTargetRemoteKeys.clear();
        unresolvedTargetCells.clear();
        unresolvedTargetRemoteKeys.clear();
        projectedUnresolvedOcclusion.clear();
        nextUnresolvedOcclusion.clear();
        entityOcclusion.disable();
        blackoutMesh = ProjectorBlackoutMesh.empty();
        projectedBlackoutView = null;
        blackoutView = null;
    }

    void commit() {
        Long2ObjectOpenHashMap<ProjectedBlockClaim> swap = projected;
        projected = nextProjected;
        nextProjected = swap;
        LongOpenHashSet blackoutSwap = projectedBlackoutGeometry;
        projectedBlackoutGeometry = blackoutGeometry;
        blackoutGeometry = blackoutSwap;
        Long2LongOpenHashMap remoteKeySwap = projectedBlackoutRemoteKeys;
        projectedBlackoutRemoteKeys = blackoutRemoteKeys;
        blackoutRemoteKeys = remoteKeySwap;
        LongOpenHashSet unresolvedSwap = projectedUnresolvedOcclusion;
        projectedUnresolvedOcclusion = nextUnresolvedOcclusion;
        nextUnresolvedOcclusion = unresolvedSwap;
        projectedBlackoutView = blackoutView;
        blackoutView = null;
    }

    void run(ProjectorDestination destination,
             PortalProjector.RtpProjectionTarget rtpTarget,
             Location eye,
             Frustum4D frustum,
             double depthBlocks,
             boolean forceStableCellResample,
             boolean forceFullSend,
             boolean buriedCellCulling,
             ProjectionRenderMode renderMode) {
        ProjectionWorldView localView = destination.localView;
        ProjectionWorldView destView = destination.destView;
        ILocalPortal dest = destination.dest;
        boolean mirrorMode = destination.mirrorMode;
        int mirrorRotationQuarterTurns = destination.mirrorRotationQuarterTurns;

        nextProjected.clear();
        blackoutGeometry.clear();
        blackoutBoundary.clear();
        blackoutRemoteKeys.clear();
        occlusionGeometry.clear();
        observerTargetCells.clear();
        observerTargetRemoteKeys.clear();
        unresolvedTargetCells.clear();
        unresolvedTargetRemoteKeys.clear();
        nextUnresolvedOcclusion.clear();
        blackoutMesh = ProjectorBlackoutMesh.empty();
        blackoutView = null;
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

        PortalFrame localFrame = portal.getFrame();
        PortalFrame remoteFrame = rtpTarget != null
            ? rtpTarget.frame()
            : mirrorMode ? localFrame.flipNormal() : destination.destAnchor.getFrame();
        double localOriginX = portal.getOrigin().getX();
        double localOriginY = portal.getOrigin().getY();
        double localOriginZ = portal.getOrigin().getZ();
        double remoteOriginX = mirrorMode ? localOriginX : destination.originX;
        double remoteOriginY = mirrorMode ? localOriginY : destination.originY;
        double remoteOriginZ = mirrorMode ? localOriginZ : destination.originZ;

        double facingX = localFrame.getNormal().x();
        double facingY = localFrame.getNormal().y();
        double facingZ = localFrame.getNormal().z();
        double eyeX = eye.getX();
        double eyeY = eye.getY();
        double eyeZ = eye.getZ();
        double eyeRelX = eyeX - localOriginX;
        double eyeRelY = eyeY - localOriginY;
        double eyeRelZ = eyeZ - localOriginZ;
        boolean eyeFrontSide = (eyeRelX * facingX + eyeRelY * facingY + eyeRelZ * facingZ) >= 0.0D;
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
        World destSampleWorld = destView.getWorld();
        ProjectorRecursivePortals.Index rootRecursiveIndex = destSampleWorld == null || Settings.PROJECTION_RECURSIVE_PORTAL_DEPTH < 0
            ? null
            : sampler.recursiveIndex(destSampleWorld, scratchRemoteEye[0], scratchRemoteEye[1], scratchRemoteEye[2], dest);
        double projectionFacingX = projectionLocalFrame.getNormal().x();
        double projectionFacingY = projectionLocalFrame.getNormal().y();
        double projectionFacingZ = projectionLocalFrame.getNormal().z();
        projectionEyeDot = (eyeRelX * projectionFacingX) + (eyeRelY * projectionFacingY) + (eyeRelZ * projectionFacingZ);
        boolean blackoutEnabled = blackout.isEnabled();
        double portalPlaneClearance = PortalProjector.portalPlaneClearance(portal.getStructure().getArea(), localFrame);
        double maxProjectionDepth = depthBlocks + portalPlaneClearance;
        double signedMinDistance = eyeFrontSide ? -maxProjectionDepth : portalPlaneClearance;
        double signedMaxDistance = eyeFrontSide ? -portalPlaneClearance : maxProjectionDepth;
        ProjectorPlaneWindow planeWindow = ProjectorPlaneWindow.create(portal.getStructure(), portal.getStructure().getArea(), projectionLocalFrame,
            localOriginX, localOriginY, localOriginZ, Settings.PROJECTION_APERTURE_PADDING_BLOCKS,
            projectionEyeDot);
        ProjectorPlaneWindow blackoutWindow = blackoutEnabled
            ? ProjectorPlaneWindow.create(portal.getStructure(), portal.getStructure().getArea(), projectionLocalFrame,
                localOriginX, localOriginY, localOriginZ, 0.0D, projectionEyeDot)
            : null;
        planeRejected = 0;
        windowRejected = 0;
        frustumRejected = 0;
        occlusionRejected = 0;
        maskedCells = 0;

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
        int normalAxis = projectionNormalDirection.x() != 0 ? 0 : (projectionNormalDirection.y() != 0 ? 1 : 2);
        int blackoutFarSign = -(projectionNormalDirection.x()
            + projectionNormalDirection.y() + projectionNormalDirection.z());
        int rightAxis = projectionRightDirection.x() != 0 ? 0 : (projectionRightDirection.y() != 0 ? 1 : 2);
        int rightSign = projectionRightDirection.x() + projectionRightDirection.y() + projectionRightDirection.z();
        int upAxis = projectionUpDirection.x() != 0 ? 0 : (projectionUpDirection.y() != 0 ? 1 : 2);
        int upSign = projectionUpDirection.x() + projectionUpDirection.y() + projectionUpDirection.z();
        int[] axisMin = scratchAxisMin;
        axisMin[0] = xa;
        axisMin[1] = ya;
        axisMin[2] = za;
        int[] axisMax = scratchAxisMax;
        axisMax[0] = xb;
        axisMax[1] = yb;
        axisMax[2] = zb;
        double[] axisOrigin = scratchAxisOrigin;
        axisOrigin[0] = localOriginX;
        axisOrigin[1] = localOriginY;
        axisOrigin[2] = localOriginZ;
        double projectionFacingNormal = normalAxis == 0 ? projectionFacingX : (normalAxis == 1 ? projectionFacingY : projectionFacingZ);
        double[] slabWindowBounds = scratchSlabWindowBounds;
        double[] blackoutSlabWindowBounds = scratchBlackoutSlabWindowBounds;
        int[] cellCoords = scratchCellCoords;
        if (blackoutEnabled) {
            blackoutView = destView;
        }
        boolean observerOcclusion = renderMode.usesObserverOcclusion();
        double localFacingNormal = normalAxis == 0 ? facingX : normalAxis == 1 ? facingY : facingZ;
        int normalStep = projectionFacingNormal > 0.0D ? -1 : 1;
        int normalStart = normalStep > 0 ? axisMin[normalAxis] : axisMax[normalAxis];
        int normalEnd = normalStep > 0 ? axisMax[normalAxis] : axisMin[normalAxis];
        int blackoutFarCoordinate = 0;
        boolean blackoutFarSliceFound = false;

        for (int n = normalStart; scanContinues(n, normalEnd, normalStep); n += normalStep) {
            double slabSignedDistance = projectionFacingNormal * ((n + 0.5D) - axisOrigin[normalAxis]);
            if (!planeWindow.slabWindow(eyeX, eyeY, eyeZ, slabSignedDistance, slabWindowBounds)) {
                continue;
            }
            int rightBlockMin = ProjectorPlaneWindow.slabBlockMin(slabWindowBounds[0], slabWindowBounds[1], rightSign, axisOrigin[rightAxis], axisMin[rightAxis]);
            int rightBlockMax = ProjectorPlaneWindow.slabBlockMax(slabWindowBounds[0], slabWindowBounds[1], rightSign, axisOrigin[rightAxis], axisMax[rightAxis]);
            int upBlockMin = ProjectorPlaneWindow.slabBlockMin(slabWindowBounds[2], slabWindowBounds[3], upSign, axisOrigin[upAxis], axisMin[upAxis]);
            int upBlockMax = ProjectorPlaneWindow.slabBlockMax(slabWindowBounds[2], slabWindowBounds[3], upSign, axisOrigin[upAxis], axisMax[upAxis]);
            boolean blackoutSlab = blackoutEnabled
                && blackoutWindow.slabWindow(eyeX, eyeY, eyeZ, slabSignedDistance, blackoutSlabWindowBounds);
            int blackoutRightBlockMin = blackoutSlab
                ? ProjectorPlaneWindow.slabBlockMin(
                    blackoutSlabWindowBounds[0], blackoutSlabWindowBounds[1], rightSign,
                    axisOrigin[rightAxis], axisMin[rightAxis])
                : 0;
            int blackoutRightBlockMax = blackoutSlab
                ? ProjectorPlaneWindow.slabBlockMax(
                    blackoutSlabWindowBounds[0], blackoutSlabWindowBounds[1], rightSign,
                    axisOrigin[rightAxis], axisMax[rightAxis])
                : -1;
            int blackoutUpBlockMin = blackoutSlab
                ? ProjectorPlaneWindow.slabBlockMin(
                    blackoutSlabWindowBounds[2], blackoutSlabWindowBounds[3], upSign,
                    axisOrigin[upAxis], axisMin[upAxis])
                : 0;
            int blackoutUpBlockMax = blackoutSlab
                ? ProjectorPlaneWindow.slabBlockMax(
                    blackoutSlabWindowBounds[2], blackoutSlabWindowBounds[3], upSign,
                    axisOrigin[upAxis], axisMax[upAxis])
                : -1;
            double cellDot = localFacingNormal * ((n + 0.5D) - axisOrigin[normalAxis]);
            if (!PortalProjector.projectsBehindPortalPlane(cellDot, eyeFrontSide, portalPlaneClearance)
                || Math.abs(cellDot) > maxProjectionDepth) {
                planeRejected = addRejectedCells(
                    planeRejected, rightBlockMin, rightBlockMax, upBlockMin, upBlockMax);
                continue;
            }
            int rightStart = rightSign > 0 ? rightBlockMin : rightBlockMax;
            int rightEnd = rightSign > 0 ? rightBlockMax : rightBlockMin;
            int upStart = upSign > 0 ? upBlockMin : upBlockMax;
            int upEnd = upSign > 0 ? upBlockMax : upBlockMin;
            cellCoords[normalAxis] = n;
            for (int r = rightStart; scanContinues(r, rightEnd, rightSign); r += rightSign) {
                cellCoords[rightAxis] = r;
                for (int u = upStart; scanContinues(u, upEnd, upSign); u += upSign) {
                    cellCoords[upAxis] = u;
                    int x = cellCoords[0];
                    int y = cellCoords[1];
                    int z = cellCoords[2];
                    double cx = x + 0.5D;
                    double cy = y + 0.5D;
                    double cz = z + 0.5D;

                    if (!planeWindow.containsRayIntersection(eyeX, eyeY, eyeZ, cx, cy, cz, slabSignedDistance)) {
                        windowRejected++;
                        continue;
                    }

                    if (!frustum.containsPrimitive(cx, cy, cz)) {
                        frustumRejected++;
                        continue;
                    }

                    long key = ProjectionCellKey.pack(x, y, z);
                    ProjectedBlockClaim previousCell = projected.get(key);
                    boolean blackoutCell = blackoutSlab
                        && blackoutWindow.containsRayIntersection(
                            eyeX, eyeY, eyeZ, cx, cy, cz, slabSignedDistance);
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
                    cellTransform.apply(cx, cy, cz, scratchRemotePoint);

                    int rx = (int) Math.floor(scratchRemotePoint[0]);
                    int ry = (int) Math.floor(scratchRemotePoint[1]);
                    int rz = (int) Math.floor(scratchRemotePoint[2]);
                    long remoteKey = ProjectionCellKey.pack(rx, ry, rz);
                    long previousRemoteKey = previousCell == null
                        ? ProjectedBlockClaim.NO_REMOTE_KEY
                        : previousCell.getLightRemoteKey();
                    if (!localView.isChunkReady(x, z)) {
                        localView.requestChunk(x, z);
                        if (previousCell != null) {
                            ProjectedBlockClaim retained = previousCell.withFullBright(blackoutEnabled);
                            nextProjected.put(key, retained);
                            rememberOcclusionBlocker(retained, observerOcclusion);
                            retainUnresolvedOcclusion(key, observerOcclusion);
                            if (blackoutCell) {
                                rememberBlackoutCell(key, remoteKey, blackoutBoundaryMask, retained);
                            }
                        }
                        if (blackoutCell && previousBlackoutMatches(destView, key, remoteKey)) {
                            addBlackoutCell(key, remoteKey, blackoutBoundaryMask);
                        }
                        continue;
                    }
                    boolean previousLightingMatches = previousCell != null
                        && previousCell.isFullBright() == blackoutEnabled;
                    if (previousLightingMatches && previousRemoteKey == remoteKey) {
                        if (!forceStableCellResample && !forceFullSend) {
                            nextProjected.put(key, previousCell);
                            rememberOcclusionBlocker(previousCell, observerOcclusion);
                            if (observerOcclusion && projectedUnresolvedOcclusion.contains(key)) {
                                addObserverTarget(key, remoteKey);
                            }
                            if (blackoutCell) {
                                rememberBlackoutCell(key, remoteKey, blackoutBoundaryMask, previousCell);
                            }
                            continue;
                        }
                    }

                    ProjectorRecursivePortals.Hit recursiveHit = rootRecursiveIndex == null
                        ? null
                        : rootRecursiveIndex.find(scratchRemotePoint[0], scratchRemotePoint[1], scratchRemotePoint[2],
                            Settings.PROJECTION_RECURSIVE_PORTAL_DEPTH);
                    ProjectorSample sample = sampler.resolve(destView,
                        scratchRemotePoint[0], scratchRemotePoint[1], scratchRemotePoint[2],
                        scratchRemoteEye[0], scratchRemoteEye[1], scratchRemoteEye[2],
                        dest,
                        Settings.PROJECTION_RECURSIVE_PORTAL_DEPTH,
                        buriedCellCulling,
                        rootRecursiveIndex,
                        recursiveHit);
                    if (sample.kind == ProjectorSample.Kind.OCCLUDED) {
                        continue;
                    }
                    if (sample.kind == ProjectorSample.Kind.NO_SAMPLE) {
                        boolean matchingRemoteUnavailable = !destView.isChunkReady(rx, rz)
                            && previousCell != null
                            && previousRemoteKey == remoteKey;
                        if (matchingRemoteUnavailable) {
                            ProjectedBlockClaim retained = previousCell.withFullBright(blackoutEnabled);
                            nextProjected.put(key, retained);
                            rememberOcclusionBlocker(retained, observerOcclusion);
                            retainUnresolvedOcclusion(key, observerOcclusion);
                            if (blackoutCell) {
                                rememberBlackoutCell(key, remoteKey, blackoutBoundaryMask, retained);
                            }
                        }
                        if (blackoutCell && previousBlackoutMatches(destView, key, remoteKey)) {
                            addBlackoutCell(key, remoteKey, blackoutBoundaryMask);
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
                        continue;
                    }
                    BlockData projectedHit;
                    if (maskAir || remoteAir) {
                        projectedHit = sampler.air();
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
                    if (observerOcclusion && recursiveHit == null) {
                        addObserverTarget(key, remoteKey);
                        rememberOcclusionBlocker(nextCell, true);
                    }
                }
            }
        }

        if (observerOcclusion && (!unresolvedTargetCells.isEmpty() || !observerTargetCells.isEmpty())) {
            viewOcclusion.setRevealMarginDegrees(Settings.PROJECTION_OCCLUSION_REVEAL_MARGIN_DEGREES);
            viewOcclusion.beginPass(
                remoteOriginX, remoteOriginY, remoteOriginZ, projectionRemoteFrame.getNormal(),
                occlusionGeometry);
            if (!occlusionGeometry.isEmpty()) {
                filterObserverTargets(destView, scratchRemoteEye[0], scratchRemoteEye[1], scratchRemoteEye[2],
                    unresolvedTargetCells, unresolvedTargetRemoteKeys);
                filterObserverTargets(destView, scratchRemoteEye[0], scratchRemoteEye[1], scratchRemoteEye[2],
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
            scratchRemoteEye[0],
            scratchRemoteEye[1],
            scratchRemoteEye[2],
            Settings.PROJECTION_OCCLUSION_REVEAL_MARGIN_DEGREES);

        if (blackoutEnabled && blackoutFarSliceFound && !blackoutBoundary.isEmpty()) {
            blackoutMesh = ProjectorBlackoutMesh.build(blackoutBoundary);
        }
        if (Settings.DEBUG) {
            recountProjectionChanges(forceFullSend);
        }
    }

    static boolean scanContinues(int coordinate, int end, int step) {
        return step > 0 ? coordinate <= end : coordinate >= end;
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
        entityOcclusion.updateEye(scratchRemoteEye[0], scratchRemoteEye[1], scratchRemoteEye[2]);
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

    void dropBlackoutDisplay() {
        blackoutMesh = new ProjectorBlackoutMesh.Result(List.of(), true);
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

    private boolean previousBlackoutMatches(ProjectionWorldView destView, long key, long remoteKey) {
        return projectedBlackoutView == destView
            && projectedBlackoutGeometry.contains(key)
            && projectedBlackoutRemoteKeys.get(key) == remoteKey;
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
            int remoteX = ProjectionCellKey.unpackX(remoteKey);
            int remoteY = ProjectionCellKey.unpackY(remoteKey);
            int remoteZ = ProjectionCellKey.unpackZ(remoteKey);
            ProjectorViewOcclusion.Visibility visibility = viewOcclusion.visibility(
                view, remoteX, remoteY, remoteZ, eyeX, eyeY, eyeZ);
            switch (visibility) {
                case HIDDEN -> {
                    nextProjected.remove(localKey);
                    occlusionRejected++;
                }
                case UNRESOLVED -> nextUnresolvedOcclusion.add(localKey);
                case VISIBLE -> {
                }
            }
        }
    }

    private void recountProjectionChanges(boolean forceFullSend) {
        enterCount = 0;
        keptCount = 0;
        maskedCells = 0;
        int retainedKeys = 0;
        for (Long2ObjectMap.Entry<ProjectedBlockClaim> entry : nextProjected.long2ObjectEntrySet()) {
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
}
