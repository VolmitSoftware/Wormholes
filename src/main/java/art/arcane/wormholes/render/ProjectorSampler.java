package art.arcane.wormholes.render;

import java.util.HashMap;
import java.util.function.Function;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.render.view.OccludedMarker;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.util.Direction;

final class ProjectorSampler {
    private static final int TRANSFORM_CACHE_LIMIT = 4096;

    private final ProjectorSampleMemo memo;
    private final ProjectorRecursivePortals recursivePortals;
    private final Function<World, ProjectionWorldView> viewLookup;
    private final BlockData airBlockData;
    private final BlockData occludedStandIn;
    private final ProjectorSample maskAirSample;
    private final HashMap<BlockData, BlockData> transformedBlockCache;
    private final double[] scratchRot;
    private Direction cachedFromNormal;
    private Direction cachedFromRight;
    private Direction cachedFromUp;
    private Direction cachedToNormal;
    private Direction cachedToRight;
    private Direction cachedToUp;
    private boolean cachedMirrorTransform;
    private int cachedMirrorRotationQuarterTurns;
    private boolean venticularPass;
    private boolean recursiveSamplesCached;
    private int remoteSamples;

    ProjectorSampler(ProjectorSampleMemo memo,
                     ProjectorRecursivePortals recursivePortals,
                     Function<World, ProjectionWorldView> viewLookup) {
        this.memo = memo;
        this.recursivePortals = recursivePortals;
        this.viewLookup = viewLookup;
        this.airBlockData = Material.AIR.createBlockData();
        this.occludedStandIn = OccludedMarker.standIn();
        this.maskAirSample = ProjectorSample.maskAir(airBlockData);
        this.transformedBlockCache = new HashMap<BlockData, BlockData>(128);
        this.scratchRot = new double[3];
        this.cachedFromNormal = null;
        this.cachedFromRight = null;
        this.cachedFromUp = null;
        this.cachedToNormal = null;
        this.cachedToRight = null;
        this.cachedToUp = null;
        this.cachedMirrorTransform = false;
        this.cachedMirrorRotationQuarterTurns = 0;
        this.venticularPass = false;
        this.recursiveSamplesCached = false;
        this.remoteSamples = 0;
    }

    BlockData air() {
        return airBlockData;
    }

    int remoteSampleCount() {
        return remoteSamples;
    }

    void resetRemoteSampleCount() {
        remoteSamples = 0;
    }

    boolean recursiveSamplesCached() {
        return recursiveSamplesCached;
    }

    void resetRecursiveSamplesCached() {
        recursiveSamplesCached = false;
    }

    boolean setVenticularPass(boolean venticularCulling) {
        if (venticularCulling == venticularPass) {
            return false;
        }
        venticularPass = venticularCulling;
        return true;
    }

    ProjectorRecursivePortals.Index recursiveIndex(World world, double eyeX, double eyeY, double eyeZ, ILocalPortal excludedPortal) {
        return recursivePortals.indexFor(world, eyeX, eyeY, eyeZ, excludedPortal);
    }

    void clearRecursivePortals() {
        recursivePortals.clear();
    }

    ProjectorSample resolve(ProjectionWorldView view,
                            double sampleX,
                            double sampleY,
                            double sampleZ,
                            double eyeX,
                            double eyeY,
                            double eyeZ,
                            ILocalPortal excludedPortal,
                            int remainingDepth,
                            boolean applyVenticularCulling,
                            ProjectorRecursivePortals.Index preparedIndex) {
        if (view == null) {
            return ProjectorSample.noSample();
        }

        World world = view.getWorld();
        ProjectorRecursivePortals.Hit hit = null;
        if (remainingDepth >= 0 && world != null) {
            ProjectorRecursivePortals.Index index = preparedIndex != null
                ? preparedIndex
                : recursivePortals.indexFor(world, eyeX, eyeY, eyeZ, excludedPortal);
            if (!index.isEmpty()) {
                hit = index.find(sampleX, sampleY, sampleZ, remainingDepth);
            }
        }
        if (hit != null) {
            recursiveSamplesCached = true;
            if (PortalProjector.shouldMaskRecursivePortalAperture(hit.traversable, hit.cycle, remainingDepth)) {
                return maskAirSample;
            }
            ProjectorSample nested = resolve(viewLookup.apply(hit.world),
                hit.pointX, hit.pointY, hit.pointZ,
                hit.eyeX, hit.eyeY, hit.eyeZ,
                hit.destinationPortal,
                remainingDepth - 1,
                false,
                null);
            if (nested.kind == ProjectorSample.Kind.NO_SAMPLE) {
                return maskAirSample;
            }
            if (nested.kind != ProjectorSample.Kind.BLOCK || !ProjectedBlockDataTransformer.requiresTransform(nested.data)) {
                return nested;
            }
            BlockData transformed = hit.mirrorProjection
                ? ProjectedBlockDataTransformer.mirror(nested.data, hit.mirrorFrame, hit.mirrorRotationQuarterTurns, scratchRot)
                : ProjectedBlockDataTransformer.transform(nested.data, hit.remoteFrame, hit.localFrame, scratchRot);
            return nested.withData(transformed);
        }

        int x = (int) Math.floor(sampleX);
        int y = (int) Math.floor(sampleY);
        int z = (int) Math.floor(sampleZ);
        boolean cacheable = applyVenticularCulling == venticularPass;
        if (cacheable) {
            ProjectorSample cached = memo.cachedSample(view, x, y, z);
            if (cached != null) {
                return cached;
            }
        }
        BlockData remoteData = view.sampleBlockData(x, y, z);
        if (remoteData == null) {
            ProjectorSample missing = ProjectorSample.noSample();
            if (cacheable) {
                memo.cacheSample(view, x, y, z, missing);
            }
            return missing;
        }
        remoteSamples++;

        long remoteKey = ProjectionCellKey.pack(x, y, z);
        ProjectorSample sample;
        if (remoteData == occludedStandIn) {
            sample = new ProjectorSample(ProjectorSample.Kind.OCCLUDED, remoteData, view, remoteKey);
        } else if (ProjectorSampleMemo.isAir(remoteData.getMaterial())) {
            sample = new ProjectorSample(ProjectorSample.Kind.REMOTE_AIR, airBlockData, view, remoteKey);
        } else if (applyVenticularCulling && memo.buriedInView(view, x, y, z, remoteData)) {
            sample = new ProjectorSample(ProjectorSample.Kind.OCCLUDED, remoteData, view, remoteKey);
        } else {
            sample = new ProjectorSample(ProjectorSample.Kind.BLOCK, remoteData, view, remoteKey);
        }
        if (cacheable) {
            memo.cacheSample(view, x, y, z, sample);
        }
        return sample;
    }

    void prepareTransformCache(PortalFrame fromFrame, PortalFrame toFrame,
                               boolean mirrorTransform, int mirrorRotationQuarterTurns) {
        if (cachedFromNormal == fromFrame.getNormal()
            && cachedFromRight == fromFrame.getRight()
            && cachedFromUp == fromFrame.getUp()
            && cachedToNormal == toFrame.getNormal()
            && cachedToRight == toFrame.getRight()
            && cachedToUp == toFrame.getUp()
            && cachedMirrorTransform == mirrorTransform
            && cachedMirrorRotationQuarterTurns == mirrorRotationQuarterTurns) {
            return;
        }
        cachedFromNormal = fromFrame.getNormal();
        cachedFromRight = fromFrame.getRight();
        cachedFromUp = fromFrame.getUp();
        cachedToNormal = toFrame.getNormal();
        cachedToRight = toFrame.getRight();
        cachedToUp = toFrame.getUp();
        cachedMirrorTransform = mirrorTransform;
        cachedMirrorRotationQuarterTurns = mirrorRotationQuarterTurns;
        transformedBlockCache.clear();
    }

    BlockData transformProjectedBlockData(BlockData source, PortalFrame fromFrame, PortalFrame toFrame,
                                          boolean mirrorMode, PortalFrame mirrorFrame,
                                          int mirrorRotationQuarterTurns) {
        if (!ProjectedBlockDataTransformer.requiresTransform(source)) {
            return source;
        }

        BlockData cached = transformedBlockCache.get(source);
        if (cached != null) {
            return cached;
        }

        BlockData transformed = mirrorMode
            ? ProjectedBlockDataTransformer.mirror(source, mirrorFrame, mirrorRotationQuarterTurns, scratchRot)
            : ProjectedBlockDataTransformer.transform(source, fromFrame, toFrame, scratchRot);
        if (transformedBlockCache.size() >= TRANSFORM_CACHE_LIMIT) {
            transformedBlockCache.clear();
        }
        transformedBlockCache.put(source, transformed);
        return transformed;
    }
}
