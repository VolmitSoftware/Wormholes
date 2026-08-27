package art.arcane.wormholes.render;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import org.bukkit.block.data.BlockData;

import art.arcane.wormholes.render.view.OccludedMarker;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.util.Direction;

final class ProjectorViewOcclusion {
    @FunctionalInterface
    interface BlockOcclusion {
        boolean occluding(BlockData data);
    }

    static final int MAX_OPACITY_CACHE_CELLS = 4_096;
    static final int MAX_HIDDEN_PROOF_CELLS = 32_768;
    static final int MAX_VOXEL_STEPS_PER_PASS = 500_000;

    private static final byte OPEN = 0;
    private static final byte BLOCKED = 1;
    private static final double MIN_TARGET_BOUND = 0.0D;
    private static final double MAX_TARGET_BOUND = 1.0D;
    private static final double PORTAL_PLANE_EPSILON = 1.0E-6D;
    private static final double TIE_EPSILON = 1.0E-10D;

    private final Long2ByteOpenHashMap opacity;
    private final Long2LongOpenHashMap hiddenBlockerProofs;
    private final LongOpenHashSet currentEyeHiddenProofs;
    private final BlockOcclusion blockOcclusion;
    private int voxelSteps;
    private int hiddenProofHits;
    private int hiddenProofRevalidations;
    private int hiddenProofInvalidations;
    private int blockerX;
    private int blockerY;
    private int blockerZ;
    private int blockerEntryAxes;
    private boolean budgetExhausted;
    private ProjectionWorldView revisionView;
    private long viewRevision;
    private boolean revisionChanged;
    private double portalOriginX;
    private double portalOriginY;
    private double portalOriginZ;
    private double portalNormalX;
    private double portalNormalY;
    private double portalNormalZ;
    private ProjectionWorldView proofView;
    private long proofRevision;
    private double proofEyeX;
    private double proofEyeY;
    private double proofEyeZ;
    private double proofPortalOriginX;
    private double proofPortalOriginY;
    private double proofPortalOriginZ;
    private double proofPortalNormalX;
    private double proofPortalNormalY;
    private double proofPortalNormalZ;
    private boolean proofContextInitialized;
    private LongSet eligibleBlockers;
    private final ProjectionOccupancyOctree eligibleOctree;
    private final double[] scratchRayStart;

    ProjectorViewOcclusion() {
        this(OccludedMarker::isOccluding);
    }

    ProjectorViewOcclusion(BlockOcclusion blockOcclusion) {
        opacity = new Long2ByteOpenHashMap(256);
        opacity.defaultReturnValue((byte) -1);
        hiddenBlockerProofs = new Long2LongOpenHashMap(256);
        currentEyeHiddenProofs = new LongOpenHashSet(256);
        this.blockOcclusion = blockOcclusion;
        this.eligibleOctree = new ProjectionOccupancyOctree();
        this.scratchRayStart = new double[3];
    }

    void beginPass(double portalOriginX,
                   double portalOriginY,
                   double portalOriginZ,
                   Direction portalNormal) {
        beginPass(portalOriginX, portalOriginY, portalOriginZ, portalNormal, null);
    }

    void beginPass(double portalOriginX,
                   double portalOriginY,
                   double portalOriginZ,
                   Direction portalNormal,
                   LongSet eligibleBlockers) {
        opacity.clear();
        voxelSteps = 0;
        hiddenProofHits = 0;
        hiddenProofRevalidations = 0;
        hiddenProofInvalidations = 0;
        blockerX = 0;
        blockerY = 0;
        blockerZ = 0;
        blockerEntryAxes = 0;
        budgetExhausted = false;
        revisionView = null;
        viewRevision = 0L;
        revisionChanged = false;
        this.portalOriginX = portalOriginX;
        this.portalOriginY = portalOriginY;
        this.portalOriginZ = portalOriginZ;
        this.portalNormalX = portalNormal.x();
        this.portalNormalY = portalNormal.y();
        this.portalNormalZ = portalNormal.z();
        this.eligibleBlockers = eligibleBlockers;
        eligibleOctree.rebuild(eligibleBlockers);
    }

    boolean visible(ProjectionWorldView view,
                    int targetX,
                    int targetY,
                    int targetZ,
                    double eyeX,
                    double eyeY,
                    double eyeZ) {
        if (!stableRevision(view)) {
            return true;
        }
        prepareHiddenProofContext(view, eyeX, eyeY, eyeZ);
        if (eligibleBlockers != null && eligibleOctree.isEmpty()) {
            return true;
        }
        long targetKey = ProjectionCellKey.pack(targetX, targetY, targetZ);
        if (hasReusableHiddenProof(targetKey, targetX, targetY, targetZ, eyeX, eyeY, eyeZ)) {
            if (!stableRevision(view)) {
                return true;
            }
            hiddenProofHits++;
            return false;
        }
        if (budgetExhausted) {
            return true;
        }
        RayResult center = traceClippedRay(view, eyeX, eyeY, eyeZ,
            targetX + 0.5D, targetY + 0.5D, targetZ + 0.5D, targetX, targetY, targetZ);
        if (center == RayResult.CLEAR || center == RayResult.BUDGET_EXHAUSTED) {
            return true;
        }
        if (blockerShadowsEntireTarget(eyeX, eyeY, eyeZ, targetX, targetY, targetZ,
            blockerX, blockerY, blockerZ)) {
            if (!stableRevision(view)) {
                return true;
            }
            rememberHiddenProof(targetKey, blockerX, blockerY, blockerZ);
            return false;
        }
        boolean hidden = opaquePlaneShadowsTarget(view, eyeX, eyeY, eyeZ, targetX, targetY, targetZ,
            blockerX, blockerY, blockerZ);
        return !hidden || !stableRevision(view);
    }

    int voxelSteps() {
        return voxelSteps;
    }

    int opacityCacheSize() {
        return opacity.size();
    }

    int hiddenProofHits() {
        return hiddenProofHits;
    }

    int hiddenProofRevalidations() {
        return hiddenProofRevalidations;
    }

    int hiddenProofInvalidations() {
        return hiddenProofInvalidations;
    }

    boolean budgetExhausted() {
        return budgetExhausted;
    }

    boolean isOccluding(BlockData data) {
        return blockOcclusion.occluding(data);
    }

    private void prepareHiddenProofContext(ProjectionWorldView view, double eyeX, double eyeY, double eyeZ) {
        if (proofContextInitialized
            && proofView == view
            && proofRevision == viewRevision
            && sameDouble(proofPortalOriginX, portalOriginX)
            && sameDouble(proofPortalOriginY, portalOriginY)
            && sameDouble(proofPortalOriginZ, portalOriginZ)
            && sameDouble(proofPortalNormalX, portalNormalX)
            && sameDouble(proofPortalNormalY, portalNormalY)
            && sameDouble(proofPortalNormalZ, portalNormalZ)) {
            updateProofValidationEye(eyeX, eyeY, eyeZ);
            return;
        }
        hiddenBlockerProofs.clear();
        currentEyeHiddenProofs.clear();
        proofView = view;
        proofRevision = viewRevision;
        proofEyeX = eyeX;
        proofEyeY = eyeY;
        proofEyeZ = eyeZ;
        proofPortalOriginX = portalOriginX;
        proofPortalOriginY = portalOriginY;
        proofPortalOriginZ = portalOriginZ;
        proofPortalNormalX = portalNormalX;
        proofPortalNormalY = portalNormalY;
        proofPortalNormalZ = portalNormalZ;
        proofContextInitialized = true;
    }

    private void updateProofValidationEye(double eyeX, double eyeY, double eyeZ) {
        if (sameDouble(proofEyeX, eyeX)
            && sameDouble(proofEyeY, eyeY)
            && sameDouble(proofEyeZ, eyeZ)) {
            return;
        }
        proofEyeX = eyeX;
        proofEyeY = eyeY;
        proofEyeZ = eyeZ;
        currentEyeHiddenProofs.clear();
    }

    private boolean hasReusableHiddenProof(long targetKey,
                                           int targetX,
                                           int targetY,
                                           int targetZ,
                                           double eyeX,
                                           double eyeY,
                                           double eyeZ) {
        if (eligibleBlockers == null || !hiddenBlockerProofs.containsKey(targetKey)) {
            return false;
        }
        long blockerKey = hiddenBlockerProofs.get(targetKey);
        if (!eligibleBlockers.contains(blockerKey)) {
            return false;
        }
        if (currentEyeHiddenProofs.contains(targetKey)) {
            return true;
        }
        int blockX = ProjectionCellKey.unpackX(blockerKey);
        int blockY = ProjectionCellKey.unpackY(blockerKey);
        int blockZ = ProjectionCellKey.unpackZ(blockerKey);
        if (!blockerShadowsEntireTarget(eyeX, eyeY, eyeZ, targetX, targetY, targetZ,
            blockX, blockY, blockZ)) {
            hiddenBlockerProofs.remove(targetKey);
            currentEyeHiddenProofs.remove(targetKey);
            hiddenProofInvalidations++;
            return false;
        }
        currentEyeHiddenProofs.add(targetKey);
        hiddenProofRevalidations++;
        return true;
    }

    private void rememberHiddenProof(long targetKey, int x, int y, int z) {
        if (eligibleBlockers == null
            || (hiddenBlockerProofs.size() >= MAX_HIDDEN_PROOF_CELLS
                && !hiddenBlockerProofs.containsKey(targetKey))) {
            return;
        }
        long blockerKey = ProjectionCellKey.pack(x, y, z);
        if (eligibleBlockers.contains(blockerKey)) {
            hiddenBlockerProofs.put(targetKey, blockerKey);
            currentEyeHiddenProofs.add(targetKey);
        }
    }

    private static boolean sameDouble(double first, double second) {
        return Double.doubleToLongBits(first) == Double.doubleToLongBits(second);
    }

    private RayResult traceClippedRay(ProjectionWorldView view,
                                      double eyeX,
                                      double eyeY,
                                      double eyeZ,
                                      double endX,
                                      double endY,
                                      double endZ,
                                      int targetX,
                                      int targetY,
                                      int targetZ) {
        clipStartToPortalPlane(eyeX, eyeY, eyeZ, endX, endY, endZ, scratchRayStart);
        return traceRay(view, scratchRayStart[0], scratchRayStart[1], scratchRayStart[2],
            endX, endY, endZ, targetX, targetY, targetZ);
    }

    private RayResult traceRay(ProjectionWorldView view,
                               double startX,
                               double startY,
                               double startZ,
                               double endX,
                               double endY,
                               double endZ,
                               int targetX,
                               int targetY,
                               int targetZ) {
        int x = floor(startX);
        int y = floor(startY);
        int z = floor(startZ);
        if (x == targetX && y == targetY && z == targetZ) {
            return RayResult.CLEAR;
        }

        double deltaX = endX - startX;
        double deltaY = endY - startY;
        double deltaZ = endZ - startZ;
        int stepX = sign(deltaX);
        int stepY = sign(deltaY);
        int stepZ = sign(deltaZ);
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / deltaX);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / deltaY);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / deltaZ);
        double tMaxX = initialBoundaryT(startX, deltaX, x, stepX);
        double tMaxY = initialBoundaryT(startY, deltaY, y, stepY);
        double tMaxZ = initialBoundaryT(startZ, deltaZ, z, stepZ);
        int remaining = Math.abs(targetX - x) + Math.abs(targetY - y) + Math.abs(targetZ - z) + 3;

        while (remaining-- > 0) {
            if (voxelSteps >= MAX_VOXEL_STEPS_PER_PASS) {
                budgetExhausted = true;
                return RayResult.BUDGET_EXHAUSTED;
            }
            voxelSteps++;
            double nextT = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
            if (nextT > 1.0D) {
                return RayResult.CLEAR;
            }
            int entryAxes = 0;
            if (tMaxX - nextT <= TIE_EPSILON) {
                x += stepX;
                tMaxX += tDeltaX;
                entryAxes |= 1;
            }
            if (tMaxY - nextT <= TIE_EPSILON) {
                y += stepY;
                tMaxY += tDeltaY;
                entryAxes |= 2;
            }
            if (tMaxZ - nextT <= TIE_EPSILON) {
                z += stepZ;
                tMaxZ += tDeltaZ;
                entryAxes |= 4;
            }
            if (x == targetX && y == targetY && z == targetZ) {
                return RayResult.CLEAR;
            }
            if (rayBlocked(view, x, y, z)) {
                blockerX = x;
                blockerY = y;
                blockerZ = z;
                blockerEntryAxes = entryAxes;
                return RayResult.BLOCKED;
            }
            if (eligibleBlockers != null) {
                int emptyLog = eligibleOctree.largestEmptyLog(x, y, z);
                if (emptyLog > 0) {
                    double tExit = ProjectionOccupancyOctree.cubeExitT(
                        x, y, z, emptyLog, startX, startY, startZ, deltaX, deltaY, deltaZ, stepX, stepY, stepZ);
                    if (tExit > 1.0D) {
                        return RayResult.CLEAR;
                    }
                    int xCrossings = boundariesBefore(tMaxX, tDeltaX, tExit);
                    int yCrossings = boundariesBefore(tMaxY, tDeltaY, tExit);
                    int zCrossings = boundariesBefore(tMaxZ, tDeltaZ, tExit);
                    x += stepX * xCrossings;
                    y += stepY * yCrossings;
                    z += stepZ * zCrossings;
                    if (xCrossings > 0) {
                        tMaxX += tDeltaX * xCrossings;
                    }
                    if (yCrossings > 0) {
                        tMaxY += tDeltaY * yCrossings;
                    }
                    if (zCrossings > 0) {
                        tMaxZ += tDeltaZ * zCrossings;
                    }
                    remaining = Math.abs(targetX - x) + Math.abs(targetY - y) + Math.abs(targetZ - z) + 3;
                }
            }
        }
        return RayResult.CLEAR;
    }

    private boolean blocked(ProjectionWorldView view, int x, int y, int z) {
        if (y < view.getMinHeight() || y >= view.getMaxHeight()) {
            return false;
        }
        long key = ProjectionCellKey.pack(x, y, z);
        byte cached = opacity.get(key);
        if (cached >= 0) {
            return cached == BLOCKED;
        }
        boolean result;
        if (!view.isChunkReady(x, z)) {
            view.requestChunk(x, z);
            result = false;
        } else {
            BlockData data = view.sampleBlockData(x, y, z);
            if (data == null) {
                view.requestChunk(x, z);
                result = false;
            } else {
                result = blockOcclusion.occluding(data);
            }
        }
        if (opacity.size() < MAX_OPACITY_CACHE_CELLS) {
            opacity.put(key, result ? BLOCKED : OPEN);
        }
        return result;
    }

    private boolean stableRevision(ProjectionWorldView view) {
        if (revisionChanged) {
            return false;
        }
        long revision = view.getRevision();
        if (revisionView == null) {
            revisionView = view;
            viewRevision = revision;
            return true;
        }
        if (revisionView != view || viewRevision != revision) {
            revisionChanged = true;
            return false;
        }
        return true;
    }

    private boolean opaquePlaneShadowsTarget(ProjectionWorldView view,
                                             double eyeX,
                                             double eyeY,
                                             double eyeZ,
                                             int targetX,
                                             int targetY,
                                             int targetZ,
                                             int blockX,
                                             int blockY,
                                             int blockZ) {
        for (int axis = 0; axis < 3; axis++) {
            if ((blockerEntryAxes & (1 << axis)) != 0
                && opaquePlaneShadowsTargetAlongAxis(view, eyeX, eyeY, eyeZ,
                    targetX, targetY, targetZ, blockX, blockY, blockZ, axis)) {
                return true;
            }
        }
        return false;
    }

    private void clipStartToPortalPlane(double eyeX,
                                        double eyeY,
                                        double eyeZ,
                                        double endX,
                                        double endY,
                                        double endZ,
                                        double[] out3) {
        double eyeDistance = signedPortalDistance(eyeX, eyeY, eyeZ);
        double endDistance = signedPortalDistance(endX, endY, endZ);
        double denominator = eyeDistance - endDistance;
        if (eyeDistance <= PORTAL_PLANE_EPSILON || endDistance >= -PORTAL_PLANE_EPSILON
            || denominator <= TIE_EPSILON) {
            out3[0] = eyeX;
            out3[1] = eyeY;
            out3[2] = eyeZ;
            return;
        }
        double t = (eyeDistance - PORTAL_PLANE_EPSILON) / denominator;
        out3[0] = eyeX + ((endX - eyeX) * t);
        out3[1] = eyeY + ((endY - eyeY) * t);
        out3[2] = eyeZ + ((endZ - eyeZ) * t);
    }

    private double signedPortalDistance(double x, double y, double z) {
        return ((x - portalOriginX) * portalNormalX)
            + ((y - portalOriginY) * portalNormalY)
            + ((z - portalOriginZ) * portalNormalZ);
    }

    private boolean opaquePlaneShadowsTargetAlongAxis(ProjectionWorldView view,
                                                      double eyeX,
                                                      double eyeY,
                                                      double eyeZ,
                                                      int targetX,
                                                      int targetY,
                                                      int targetZ,
                                                      int blockX,
                                                      int blockY,
                                                      int blockZ,
                                                      int axis) {
        double deltaX = targetX + 0.5D - eyeX;
        double deltaY = targetY + 0.5D - eyeY;
        double deltaZ = targetZ + 0.5D - eyeZ;
        int firstOtherAxis = (axis + 1) % 3;
        int secondOtherAxis = (axis + 2) % 3;
        double eyeAxis = coordinate(axis, eyeX, eyeY, eyeZ);
        double eyeFirst = coordinate(firstOtherAxis, eyeX, eyeY, eyeZ);
        double eyeSecond = coordinate(secondOtherAxis, eyeX, eyeY, eyeZ);
        double centerDelta = coordinate(axis, deltaX, deltaY, deltaZ);
        int direction = sign(centerDelta);
        if (direction == 0) {
            return false;
        }
        int blockAxis = coordinate(axis, blockX, blockY, blockZ);
        int targetAxis = coordinate(axis, targetX, targetY, targetZ);
        if ((direction > 0 && targetAxis <= blockAxis)
            || (direction < 0 && targetAxis >= blockAxis)) {
            return false;
        }
        double plane = blockAxis + (direction < 0 ? 1.0D : 0.0D);
        double minFirst = Double.POSITIVE_INFINITY;
        double maxFirst = Double.NEGATIVE_INFINITY;
        double minSecond = Double.POSITIVE_INFINITY;
        double maxSecond = Double.NEGATIVE_INFINITY;

        for (int xOffset = 0; xOffset <= 1; xOffset++) {
            for (int yOffset = 0; yOffset <= 1; yOffset++) {
                for (int zOffset = 0; zOffset <= 1; zOffset++) {
                    double endX = targetX + xOffset;
                    double endY = targetY + yOffset;
                    double endZ = targetZ + zOffset;
                    double axisDelta = coordinate(axis, endX, endY, endZ) - eyeAxis;
                    if (sign(axisDelta) != direction) {
                        return false;
                    }
                    double t = (plane - eyeAxis) / axisDelta;
                    if (t <= TIE_EPSILON || t > 1.0D + TIE_EPSILON) {
                        return false;
                    }
                    double projectedFirst = eyeFirst
                        + (coordinate(firstOtherAxis, endX, endY, endZ) - eyeFirst) * t;
                    double projectedSecond = eyeSecond
                        + (coordinate(secondOtherAxis, endX, endY, endZ) - eyeSecond) * t;
                    minFirst = Math.min(minFirst, projectedFirst);
                    maxFirst = Math.max(maxFirst, projectedFirst);
                    minSecond = Math.min(minSecond, projectedSecond);
                    maxSecond = Math.max(maxSecond, projectedSecond);
                }
            }
        }

        int minFirstCell = floor(minFirst - TIE_EPSILON);
        int maxFirstCell = floor(maxFirst + TIE_EPSILON);
        int minSecondCell = floor(minSecond - TIE_EPSILON);
        int maxSecondCell = floor(maxSecond + TIE_EPSILON);
        int firstSpan = maxFirstCell - minFirstCell + 1;
        int secondSpan = maxSecondCell - minSecondCell + 1;
        if (firstSpan <= 0 || secondSpan <= 0 || firstSpan * secondSpan > 64) {
            return false;
        }

        for (int first = minFirstCell; first <= maxFirstCell; first++) {
            for (int second = minSecondCell; second <= maxSecondCell; second++) {
                if (voxelSteps >= MAX_VOXEL_STEPS_PER_PASS) {
                    budgetExhausted = true;
                    return false;
                }
                voxelSteps++;
                int x = axisCoordinate(0, axis, blockAxis, firstOtherAxis, first, second);
                int y = axisCoordinate(1, axis, blockAxis, firstOtherAxis, first, second);
                int z = axisCoordinate(2, axis, blockAxis, firstOtherAxis, first, second);
                if (!rayBlocked(view, x, y, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean blockerShadowsEntireTarget(double eyeX,
                                                      double eyeY,
                                                      double eyeZ,
                                                      int targetX,
                                                      int targetY,
                                                      int targetZ,
                                                      int blockX,
                                                      int blockY,
                                                      int blockZ) {
        return segmentIntersectsBlock(eyeX, eyeY, eyeZ, targetX + MIN_TARGET_BOUND, targetY + MIN_TARGET_BOUND, targetZ + MIN_TARGET_BOUND, blockX, blockY, blockZ)
            && segmentIntersectsBlock(eyeX, eyeY, eyeZ, targetX + MIN_TARGET_BOUND, targetY + MIN_TARGET_BOUND, targetZ + MAX_TARGET_BOUND, blockX, blockY, blockZ)
            && segmentIntersectsBlock(eyeX, eyeY, eyeZ, targetX + MIN_TARGET_BOUND, targetY + MAX_TARGET_BOUND, targetZ + MIN_TARGET_BOUND, blockX, blockY, blockZ)
            && segmentIntersectsBlock(eyeX, eyeY, eyeZ, targetX + MIN_TARGET_BOUND, targetY + MAX_TARGET_BOUND, targetZ + MAX_TARGET_BOUND, blockX, blockY, blockZ)
            && segmentIntersectsBlock(eyeX, eyeY, eyeZ, targetX + MAX_TARGET_BOUND, targetY + MIN_TARGET_BOUND, targetZ + MIN_TARGET_BOUND, blockX, blockY, blockZ)
            && segmentIntersectsBlock(eyeX, eyeY, eyeZ, targetX + MAX_TARGET_BOUND, targetY + MIN_TARGET_BOUND, targetZ + MAX_TARGET_BOUND, blockX, blockY, blockZ)
            && segmentIntersectsBlock(eyeX, eyeY, eyeZ, targetX + MAX_TARGET_BOUND, targetY + MAX_TARGET_BOUND, targetZ + MIN_TARGET_BOUND, blockX, blockY, blockZ)
            && segmentIntersectsBlock(eyeX, eyeY, eyeZ, targetX + MAX_TARGET_BOUND, targetY + MAX_TARGET_BOUND, targetZ + MAX_TARGET_BOUND, blockX, blockY, blockZ);
    }

    private static boolean segmentIntersectsBlock(double startX,
                                                  double startY,
                                                  double startZ,
                                                  double endX,
                                                  double endY,
                                                  double endZ,
                                                  int blockX,
                                                  int blockY,
                                                  int blockZ) {
        double deltaX = endX - startX;
        double deltaY = endY - startY;
        double deltaZ = endZ - startZ;
        double tMin = 0.0D;
        double tMax = 1.0D;

        if (Math.abs(deltaX) <= TIE_EPSILON) {
            if (startX < blockX || startX > blockX + 1.0D) {
                return false;
            }
        } else {
            double first = (blockX - startX) / deltaX;
            double second = ((blockX + 1.0D) - startX) / deltaX;
            tMin = Math.max(tMin, Math.min(first, second));
            tMax = Math.min(tMax, Math.max(first, second));
            if (tMin > tMax) {
                return false;
            }
        }
        if (Math.abs(deltaY) <= TIE_EPSILON) {
            if (startY < blockY || startY > blockY + 1.0D) {
                return false;
            }
        } else {
            double first = (blockY - startY) / deltaY;
            double second = ((blockY + 1.0D) - startY) / deltaY;
            tMin = Math.max(tMin, Math.min(first, second));
            tMax = Math.min(tMax, Math.max(first, second));
            if (tMin > tMax) {
                return false;
            }
        }
        if (Math.abs(deltaZ) <= TIE_EPSILON) {
            if (startZ < blockZ || startZ > blockZ + 1.0D) {
                return false;
            }
        } else {
            double first = (blockZ - startZ) / deltaZ;
            double second = ((blockZ + 1.0D) - startZ) / deltaZ;
            tMin = Math.max(tMin, Math.min(first, second));
            tMax = Math.min(tMax, Math.max(first, second));
            if (tMin > tMax) {
                return false;
            }
        }
        return tMin < 1.0D - TIE_EPSILON;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static int boundariesBefore(double first, double interval, double limit) {
        if (!Double.isFinite(first) || !Double.isFinite(interval) || interval <= 0.0D || first >= limit) {
            return 0;
        }
        double last = Math.nextDown(limit);
        if (first > last) {
            return 0;
        }
        return (int) Math.floor((last - first) / interval) + 1;
    }

    private boolean rayBlocked(ProjectionWorldView view, int x, int y, int z) {
        if (eligibleBlockers != null) {
            return eligibleBlockers.contains(ProjectionCellKey.pack(x, y, z));
        }
        return blocked(view, x, y, z);
    }

    private static double coordinate(int axis, double x, double y, double z) {
        return switch (axis) {
            case 0 -> x;
            case 1 -> y;
            default -> z;
        };
    }

    private static int coordinate(int axis, int x, int y, int z) {
        return switch (axis) {
            case 0 -> x;
            case 1 -> y;
            default -> z;
        };
    }

    private static int axisCoordinate(int requestedAxis,
                                      int primaryAxis,
                                      int primary,
                                      int firstOtherAxis,
                                      int first,
                                      int second) {
        if (requestedAxis == primaryAxis) {
            return primary;
        }
        if (requestedAxis == firstOtherAxis) {
            return first;
        }
        return second;
    }

    private static int sign(double value) {
        return value > 0.0D ? 1 : value < 0.0D ? -1 : 0;
    }

    private static double initialBoundaryT(double start, double delta, int cell, int step) {
        if (step > 0) {
            return ((cell + 1.0D) - start) / delta;
        }
        if (step < 0) {
            return (start - cell) / -delta;
        }
        return Double.POSITIVE_INFINITY;
    }

    private enum RayResult {
        CLEAR,
        BLOCKED,
        BUDGET_EXHAUSTED
    }
}
