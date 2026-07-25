package art.arcane.wormholes.render;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

final class ProjectorCellScan {
    private final ILocalPortal portal;
    private final ProjectorSampler sampler;
    private final ProjectorSampleMemo memo;
    private final ProjectorBlackoutSeal blackout;
    private final ProjectorFrameTransform cellTransform;
    private final double[] scratchRot;
    private final double[] scratchRemotePoint;
    private final double[] scratchRemoteEye;
    private final int[] scratchAxisMin;
    private final int[] scratchAxisMax;
    private final double[] scratchAxisOrigin;
    private final double[] scratchSlabWindowBounds;
    private final int[] scratchCellCoords;
    private Long2ObjectOpenHashMap<ProjectedBlockClaim> projected;
    private Long2ObjectOpenHashMap<ProjectedBlockClaim> nextProjected;
    private PortalFrame projectionLocalFrame;
    private PortalFrame projectionRemoteFrame;
    private double projectionEyeDot;
    private int enterCount;
    private int exitCount;
    private int keptCount;
    private int planeRejected;
    private int windowRejected;
    private int frustumRejected;
    private int maskedCells;

    ProjectorCellScan(ILocalPortal portal,
                      ProjectorSampler sampler,
                      ProjectorSampleMemo memo,
                      ProjectorBlackoutSeal blackout) {
        this.portal = portal;
        this.sampler = sampler;
        this.memo = memo;
        this.blackout = blackout;
        this.cellTransform = new ProjectorFrameTransform();
        this.scratchRot = new double[3];
        this.scratchRemotePoint = new double[3];
        this.scratchRemoteEye = new double[3];
        this.scratchAxisMin = new int[3];
        this.scratchAxisMax = new int[3];
        this.scratchAxisOrigin = new double[3];
        this.scratchSlabWindowBounds = new double[4];
        this.scratchCellCoords = new int[3];
        this.projected = new Long2ObjectOpenHashMap<ProjectedBlockClaim>(256);
        this.nextProjected = new Long2ObjectOpenHashMap<ProjectedBlockClaim>(256);
    }

    Long2ObjectOpenHashMap<ProjectedBlockClaim> claims() {
        return nextProjected;
    }

    boolean hasProjection() {
        return !projected.isEmpty();
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

    int maskedCells() {
        return maskedCells;
    }

    void clear() {
        projected.clear();
        nextProjected.clear();
    }

    void commit() {
        Long2ObjectOpenHashMap<ProjectedBlockClaim> swap = projected;
        projected = nextProjected;
        nextProjected = swap;
    }

    void run(ProjectorDestination destination,
             PortalProjector.RtpProjectionTarget rtpTarget,
             Location eye,
             Frustum4D frustum,
             double depthBlocks,
             boolean forceStableCellResample,
             boolean forceFullSend,
             boolean venticularCulling) {
        ProjectionWorldView localView = destination.localView;
        ProjectionWorldView destView = destination.destView;
        ILocalPortal dest = destination.dest;
        boolean mirrorMode = destination.mirrorMode;
        int mirrorRotationQuarterTurns = destination.mirrorRotationQuarterTurns;

        nextProjected.clear();
        enterCount = 0;
        keptCount = 0;
        int carriedOverCells = 0;

        int localMinY = localView.getMinHeight();
        int localMaxY = localView.getMaxHeight() - 1;
        AxisAlignedBB area = frustum.getRegion();
        int xa = (int) Math.floor(area.getXa());
        int ya = Math.max((int) Math.floor(area.getYa()), localMinY);
        int za = (int) Math.floor(area.getZa());
        int xb = (int) Math.floor(area.getXb());
        int yb = Math.min((int) Math.floor(area.getYb()), localMaxY);
        int zb = (int) Math.floor(area.getZb());

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
        double portalPlaneClearance = PortalProjector.portalPlaneClearance(portal.getStructure().getArea(), localFrame);
        double maxProjectionDepth = depthBlocks + portalPlaneClearance;
        double signedMinDistance = eyeFrontSide ? -maxProjectionDepth : portalPlaneClearance;
        double signedMaxDistance = eyeFrontSide ? -portalPlaneClearance : maxProjectionDepth;
        ProjectorPlaneWindow planeWindow = ProjectorPlaneWindow.create(portal.getStructure(), portal.getStructure().getArea(), projectionLocalFrame,
            localOriginX, localOriginY, localOriginZ, Settings.PROJECTION_APERTURE_PADDING_BLOCKS,
            projectionEyeDot);
        planeRejected = 0;
        windowRejected = 0;
        frustumRejected = 0;
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
        int[] cellCoords = scratchCellCoords;
        boolean blackoutEnabled = blackout.isEnabled();
        if (blackoutEnabled) {
            double blackoutEyeNormal = normalAxis == 0 ? eyeX : (normalAxis == 1 ? eyeY : eyeZ);
            blackout.configureBands(portal.getStructure().getArea(), normalAxis, rightAxis, upAxis,
                axisOrigin[normalAxis], blackoutEyeNormal, depthBlocks);
        }

        for (int n = axisMin[normalAxis]; n <= axisMax[normalAxis]; n++) {
            double slabSignedDistance = projectionFacingNormal * ((n + 0.5D) - axisOrigin[normalAxis]);
            if (!planeWindow.slabWindow(eyeX, eyeY, eyeZ, slabSignedDistance, slabWindowBounds)) {
                continue;
            }
            int rightBlockMin = ProjectorPlaneWindow.slabBlockMin(slabWindowBounds[0], slabWindowBounds[1], rightSign, axisOrigin[rightAxis], axisMin[rightAxis]);
            int rightBlockMax = ProjectorPlaneWindow.slabBlockMax(slabWindowBounds[0], slabWindowBounds[1], rightSign, axisOrigin[rightAxis], axisMax[rightAxis]);
            int upBlockMin = ProjectorPlaneWindow.slabBlockMin(slabWindowBounds[2], slabWindowBounds[3], upSign, axisOrigin[upAxis], axisMin[upAxis]);
            int upBlockMax = ProjectorPlaneWindow.slabBlockMax(slabWindowBounds[2], slabWindowBounds[3], upSign, axisOrigin[upAxis], axisMax[upAxis]);
            cellCoords[normalAxis] = n;
            for (int r = rightBlockMin; r <= rightBlockMax; r++) {
                cellCoords[rightAxis] = r;
                for (int u = upBlockMin; u <= upBlockMax; u++) {
                    cellCoords[upAxis] = u;
                    int x = cellCoords[0];
                    int y = cellCoords[1];
                    int z = cellCoords[2];
                    double cx = x + 0.5D;
                    double cy = y + 0.5D;
                    double cz = z + 0.5D;

                    double cellRelX = cx - localOriginX;
                    double cellRelY = cy - localOriginY;
                    double cellRelZ = cz - localOriginZ;
                    double cellDot = cellRelX * facingX + cellRelY * facingY + cellRelZ * facingZ;
                    if (!PortalProjector.projectsBehindPortalPlane(cellDot, eyeFrontSide, portalPlaneClearance)) {
                        planeRejected++;
                        continue;
                    }

                    if (Math.abs(cellDot) > maxProjectionDepth) {
                        planeRejected++;
                        continue;
                    }

                    double projectionCellDot = (cellRelX * projectionFacingX) + (cellRelY * projectionFacingY) + (cellRelZ * projectionFacingZ);
                    if (!planeWindow.containsRayIntersection(eyeX, eyeY, eyeZ, cx, cy, cz, projectionCellDot)) {
                        windowRejected++;
                        continue;
                    }

                    if (!frustum.containsPrimitive(cx, cy, cz)) {
                        frustumRejected++;
                        continue;
                    }

                    long key = ProjectionCellKey.pack(x, y, z);
                    cellTransform.apply(cx, cy, cz, scratchRemotePoint);

                    int rx = (int) Math.floor(scratchRemotePoint[0]);
                    int ry = (int) Math.floor(scratchRemotePoint[1]);
                    int rz = (int) Math.floor(scratchRemotePoint[2]);
                    long remoteKey = ProjectionCellKey.pack(rx, ry, rz);
                    ProjectedBlockClaim previousCell = projected.get(key);
                    BlockData previousData = previousCell == null ? null : previousCell.getData();
                    long previousRemoteKey = previousCell == null
                        ? ProjectedBlockClaim.NO_REMOTE_KEY
                        : previousCell.getLightRemoteKey();
                    if (!localView.isChunkReady(x, z)) {
                        localView.requestChunk(x, z);
                        if (previousCell != null) {
                            nextProjected.put(key, previousCell);
                            carriedOverCells++;
                            keptCount++;
                        }
                        continue;
                    }
                    if (previousCell != null && previousRemoteKey == remoteKey) {
                        if (!forceStableCellResample && !forceFullSend) {
                            nextProjected.put(key, previousCell);
                            carriedOverCells++;
                            keptCount++;
                            continue;
                        }
                    }

                    ProjectorSample sample = sampler.resolve(destView,
                        scratchRemotePoint[0], scratchRemotePoint[1], scratchRemotePoint[2],
                        scratchRemoteEye[0], scratchRemoteEye[1], scratchRemoteEye[2],
                        dest,
                        Settings.PROJECTION_RECURSIVE_PORTAL_DEPTH,
                        venticularCulling,
                        rootRecursiveIndex);
                    if (sample.kind == ProjectorSample.Kind.OCCLUDED) {
                        continue;
                    }
                    if (blackoutEnabled) {
                        boolean blackoutOccluding = sample.kind == ProjectorSample.Kind.BLOCK && sample.data.getMaterial().isOccluding();
                        if (blackout.covers(sample.kind, blackoutOccluding) && blackout.sealsCell(cx, cy, cz)) {
                            BlockData blackoutData = blackout.data();
                            ProjectedBlockClaim blackoutClaim = (sample.kind == ProjectorSample.Kind.REMOTE_AIR || sample.kind == ProjectorSample.Kind.BLOCK)
                                ? sample.asClaim(blackoutData)
                                : new ProjectedBlockClaim(blackoutData, null, remoteKey, false);
                            if (forceFullSend || !blackoutData.equals(previousData)) {
                                enterCount++;
                            } else {
                                keptCount++;
                            }
                            if (previousCell != null) {
                                carriedOverCells++;
                            }
                            nextProjected.put(key, blackoutClaim);
                            continue;
                        }
                    }
                    if (sample.kind == ProjectorSample.Kind.NO_SAMPLE) {
                        if (!destView.isChunkReady(rx, rz) && previousCell != null && previousRemoteKey == remoteKey) {
                            nextProjected.put(key, previousCell);
                            carriedOverCells++;
                            keptCount++;
                        }
                        continue;
                    }

                    BlockData projectedHit;
                    boolean maskAir = sample.kind == ProjectorSample.Kind.MASK_AIR;
                    boolean remoteAir = sample.kind == ProjectorSample.Kind.REMOTE_AIR;
                    if (maskAir || remoteAir) {
                        boolean localAir = remoteAir && memo.isLocalAir(localView, x, y, z);
                        if (!PortalProjector.shouldProjectAirSample(sample.kind, localAir)) {
                            continue;
                        }
                        if (maskAir) {
                            maskedCells++;
                        }
                        projectedHit = sampler.air();
                    } else {
                        projectedHit = sampler.transformProjectedBlockData(sample.data, projectionRemoteFrame, projectionLocalFrame,
                            mirrorMode, localFrame, mirrorRotationQuarterTurns);
                    }

                    if (forceFullSend || !projectedHit.equals(previousData)) {
                        enterCount++;
                    } else {
                        keptCount++;
                    }
                    ProjectedBlockClaim nextCell = sample.matchesClaim(previousCell, projectedHit, maskAir)
                        ? previousCell
                        : sample.asClaim(projectedHit);
                    if (previousCell != null) {
                        carriedOverCells++;
                    }
                    nextProjected.put(key, nextCell);
                }
            }
        }

        exitCount = projected.size() - carriedOverCells;
    }
}
