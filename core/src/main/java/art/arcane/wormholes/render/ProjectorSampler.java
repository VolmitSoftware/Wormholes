package art.arcane.wormholes.render;

import java.util.function.Function;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.render.view.ProjectionMaterialView;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.util.Direction;

public final class ProjectorSampler<B, M, W, P extends IPortal, V extends ProjectionMaterialView<B, M>> {
    private static final int TRANSFORM_CACHE_LIMIT = 4096;

    private final ProjectorSampleMemo<B, M, V> memo;
    private final ProjectorRecursivePortals<W, P> recursivePortals;
    private final Function<W, V> viewLookup;
    private final Function<V, W> worldLookup;
    private final ProjectionBlockTypes<B, M> blocks;
    private final B airBlockData;
    private final B occludedStandIn;
    private final ProjectorSample<B, V> maskAirSample;
    private final Object2ObjectOpenHashMap<B, B> transformedBlockCache;
    private final ProjectorRecursivePortals.RecursionPath recursionPath;
    private final double[] scratchRot;
    private Direction cachedFromNormal;
    private Direction cachedFromRight;
    private Direction cachedFromUp;
    private Direction cachedToNormal;
    private Direction cachedToRight;
    private Direction cachedToUp;
    private boolean cachedMirrorTransform;
    private int cachedMirrorRotationQuarterTurns;
    private boolean buriedCellCullingPass;
    private boolean recursiveSamplesCached;
    private int remoteSamples;

    public ProjectorSampler(Options<B, M, W, P, V> options) {
        this.memo = options.memo();
        this.recursivePortals = options.recursivePortals();
        this.viewLookup = options.viewLookup();
        this.worldLookup = options.worldLookup();
        this.blocks = memo.blocks();
        this.airBlockData = blocks.air();
        this.occludedStandIn = blocks.occluded();
        this.maskAirSample = ProjectorSample.maskAir(airBlockData);
        this.transformedBlockCache = new Object2ObjectOpenHashMap<B, B>(128);
        this.recursionPath = new ProjectorRecursivePortals.RecursionPath();
        this.scratchRot = new double[3];
        this.cachedFromNormal = null;
        this.cachedFromRight = null;
        this.cachedFromUp = null;
        this.cachedToNormal = null;
        this.cachedToRight = null;
        this.cachedToUp = null;
        this.cachedMirrorTransform = false;
        this.cachedMirrorRotationQuarterTurns = 0;
        this.buriedCellCullingPass = false;
        this.recursiveSamplesCached = false;
        this.remoteSamples = 0;
    }

    public W world(V view) {
        return worldLookup.apply(view);
    }

    public B air() {
        return airBlockData;
    }

    public int remoteSampleCount() {
        return remoteSamples;
    }

    public void resetRemoteSampleCount() {
        remoteSamples = 0;
    }

    public boolean recursiveSamplesCached() {
        return recursiveSamplesCached;
    }

    public void resetRecursiveSamplesCached() {
        recursiveSamplesCached = false;
    }

    public boolean setBuriedCellCullingPass(boolean buriedCellCulling) {
        if (buriedCellCulling == buriedCellCullingPass) {
            return false;
        }
        buriedCellCullingPass = buriedCellCulling;
        return true;
    }

    public ProjectorRecursivePortals<W, P>.Index recursiveIndex(W world, double eyeX, double eyeY, double eyeZ, P excludedPortal) {
        return recursivePortals.indexFor(world, eyeX, eyeY, eyeZ, excludedPortal);
    }

    public void clearRecursivePortals() {
        recursivePortals.clear();
    }

    public ProjectorSample<B, V> resolve(V view,
                            double sampleX,
                            double sampleY,
                            double sampleZ,
                            double eyeX,
                            double eyeY,
                            double eyeZ,
                            P excludedPortal,
                            int remainingDepth,
                            boolean applyBuriedCellCulling,
                            ProjectorRecursivePortals<W, P>.Index preparedIndex,
                            ProjectorRecursivePortals.Hit<W, P> preparedHit) {
        recursionPath.clear();
        return resolveNested(view, sampleX, sampleY, sampleZ, eyeX, eyeY, eyeZ, excludedPortal, remainingDepth,
            applyBuriedCellCulling, preparedIndex, preparedHit);
    }

    private ProjectorSample<B, V> resolveNested(V view,
                                          double sampleX,
                                          double sampleY,
                                          double sampleZ,
                                          double eyeX,
                                          double eyeY,
                                          double eyeZ,
                                          P excludedPortal,
                                          int remainingDepth,
                                          boolean applyBuriedCellCulling,
                                          ProjectorRecursivePortals<W, P>.Index preparedIndex,
                                          ProjectorRecursivePortals.Hit<W, P> preparedHit) {
        if (view == null) {
            return ProjectorSample.noSample();
        }

        W world = worldLookup.apply(view);
        ProjectorRecursivePortals.Hit<W, P> hit = preparedHit;
        if (hit == null && preparedIndex == null && remainingDepth >= 0 && world != null) {
            ProjectorRecursivePortals<W, P>.Index index = recursivePortals.indexFor(world, eyeX, eyeY, eyeZ, excludedPortal);
            if (!index.isEmpty()) {
                hit = index.find(sampleX, sampleY, sampleZ, remainingDepth, recursionPath);
            }
        }
        if (hit != null) {
            recursiveSamplesCached = true;
            if (shouldMaskRecursivePortalAperture(hit.traversable, hit.cycle, remainingDepth)) {
                return maskAirSample;
            }
            ProjectorSample<B, V> nested;
            recursionPath.push(hit.portalId);
            try {
                nested = resolveNested(viewLookup.apply(hit.world),
                    hit.pointX, hit.pointY, hit.pointZ,
                    hit.eyeX, hit.eyeY, hit.eyeZ,
                    hit.destinationPortal,
                    remainingDepth - 1,
                    false,
                    null,
                    null);
            } finally {
                recursionPath.pop();
            }
            if (nested.kind == ProjectorSample.Kind.NO_SAMPLE) {
                return maskAirSample;
            }
            if (nested.kind != ProjectorSample.Kind.BLOCK || !blocks.requiresTransform(nested.data)) {
                return nested;
            }
            B transformed = hit.mirrorProjection
                ? blocks.transform(nested.data, DirectionMapping.mirror(hit.mirrorFrame, hit.mirrorRotationQuarterTurns, scratchRot))
                : blocks.transform(nested.data, DirectionMapping.between(hit.remoteFrame, hit.localFrame, scratchRot));
            return nested.withData(transformed);
        }

        int x = (int) Math.floor(sampleX);
        int y = (int) Math.floor(sampleY);
        int z = (int) Math.floor(sampleZ);
        boolean cacheable = applyBuriedCellCulling == buriedCellCullingPass;
        if (cacheable) {
            ProjectorSample<B, V> cached = memo.cachedSample(view, x, y, z);
            if (cached != null) {
                return cached;
            }
        }
        B remoteData = view.sampleBlockData(x, y, z);
        if (remoteData == null) {
            ProjectorSample<B, V> missing = ProjectorSample.noSample();
            if (cacheable) {
                memo.cacheSample(view, x, y, z, missing);
            }
            return missing;
        }
        remoteSamples++;

        long remoteKey = ProjectionCellKey.pack(x, y, z);
        ProjectorSample<B, V> sample;
        if (remoteData == occludedStandIn) {
            sample = new ProjectorSample<B, V>(ProjectorSample.Kind.OCCLUDED, remoteData, view, remoteKey);
        } else if (blocks.isAir(blocks.material(remoteData))) {
            sample = new ProjectorSample<B, V>(ProjectorSample.Kind.REMOTE_AIR, airBlockData, view, remoteKey);
        } else {
            int occlusionDepth = applyBuriedCellCulling
                ? memo.occlusionDepthInView(view, x, y, z, remoteData)
                : 0;
            ProjectorSample.Kind kind = switch (occlusionDepth) {
                case 1 -> ProjectorSample.Kind.BACKING_BLOCK;
                case 2 -> ProjectorSample.Kind.OCCLUDED;
                default -> ProjectorSample.Kind.BLOCK;
            };
            sample = new ProjectorSample<B, V>(kind, remoteData, view, remoteKey);
        }
        if (cacheable) {
            memo.cacheSample(view, x, y, z, sample);
        }
        return sample;
    }

    public void prepareTransformCache(PortalFrame fromFrame, PortalFrame toFrame,
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

    public B transformProjectedBlockData(B source, PortalFrame fromFrame, PortalFrame toFrame,
                                          boolean mirrorMode, PortalFrame mirrorFrame,
                                          int mirrorRotationQuarterTurns) {
        if (!blocks.requiresTransform(source)) {
            return source;
        }

        B cached = transformedBlockCache.get(source);
        if (cached != null) {
            return cached;
        }

        B transformed = mirrorMode
            ? blocks.transform(source, DirectionMapping.mirror(mirrorFrame, mirrorRotationQuarterTurns, scratchRot))
            : blocks.transform(source, DirectionMapping.between(fromFrame, toFrame, scratchRot));
        if (transformedBlockCache.size() >= TRANSFORM_CACHE_LIMIT) {
            transformedBlockCache.clear();
        }
        transformedBlockCache.put(source, transformed);
        return transformed;
    }

    public static boolean shouldMaskRecursivePortalAperture(boolean traversable, boolean cycle, int remainingDepth) {
        return !traversable || cycle || remainingDepth <= 0;
    }

    public record Options<B, M, W, P extends IPortal, V extends ProjectionMaterialView<B, M>>(
        ProjectorSampleMemo<B, M, V> memo,
        ProjectorRecursivePortals<W, P> recursivePortals,
        Function<W, V> viewLookup,
        Function<V, W> worldLookup) {
    }
}
