package art.arcane.wormholes.render.plate;

import java.util.Objects;
import art.arcane.wormholes.portal.PortalCellAperture;
import art.arcane.wormholes.render.ProjectionBlockTypes;
import art.arcane.wormholes.render.DirectionMapping;
import art.arcane.wormholes.render.view.ProjectionContentView;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;


import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.render.ProjectionCellKey;
import art.arcane.wormholes.render.ProjectorFrameTransform;
import art.arcane.wormholes.render.ProjectorSample;
import art.arcane.wormholes.render.blockentity.BlockEntitySample;
import art.arcane.wormholes.render.lod.LodPolicy;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

/**
 * Samples the portal-scoped volume behind one face of a portal through the destination view and
 * produces an immutable {@link ViewPlate}. The build is resumable so it can be spread over several
 * steps; the classification of every cell matches {@code ProjectorSampler.resolve} without recursion,
 * so the per-observer scan can substitute plate cells for sampler calls.
 */
public final class ViewPlateBuilder {
    private static final int TRANSFORM_CACHE_LIMIT = 4096;
    private static final int BURIED_PROBE_MARGIN = 2;

    private ViewPlateBuilder() {
    }

    public record Request<B, M, V extends ProjectionContentView<B, M>>(ViewPlateKey key,
                          PortalCellAperture aperture,
                          V destView,
                          PortalFrame localFrame,
                          PortalFrame remoteFrame,
                          double localOriginX,
                          double localOriginY,
                          double localOriginZ,
                          double remoteOriginX,
                          double remoteOriginY,
                          double remoteOriginZ,
                          boolean mirrorMode,
                          int mirrorRotationQuarterTurns,
                          double depthBlocks,
                          double lateralBlocks,
                          double aperturePadding,
                          boolean buriedCellCulling,
                          B air,
                          LodPolicy lod,
                          boolean blockEntities,
                          long destinationRevision,
                          long transformRevision,
                          long trackerVersion,
                          ProjectionBlockTypes<B, M> blocks) {
        public Request {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(aperture, "aperture");
            Objects.requireNonNull(blocks, "blocks");
            Objects.requireNonNull(destView, "destView");
            Objects.requireNonNull(localFrame, "localFrame");
            Objects.requireNonNull(remoteFrame, "remoteFrame");
            Objects.requireNonNull(air, "air");
            lod = lod == null ? LodPolicy.NONE : lod;
        }

        public Request<B, M, V> withDestView(V view) {
            return new Request<B, M, V>(key, aperture, view, localFrame, remoteFrame,
                localOriginX, localOriginY, localOriginZ, remoteOriginX, remoteOriginY, remoteOriginZ,
                mirrorMode, mirrorRotationQuarterTurns, depthBlocks, lateralBlocks, aperturePadding, buriedCellCulling,
                air, lod, blockEntities, destinationRevision, transformRevision, trackerVersion, blocks);
        }
    }

    public record Footprint(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, long predictedBytes) {
        public int chunkCount() {
            if (maxChunkX < minChunkX || maxChunkZ < minChunkZ) {
                return 0;
            }
            return ((maxChunkX - minChunkX) + 1) * ((maxChunkZ - minChunkZ) + 1);
        }
    }

    public abstract static class Job<B, W> {
        private final ViewPlateKey key;

        protected Job(ViewPlateKey key) {
            this.key = Objects.requireNonNull(key, "key");
        }

        public ViewPlateKey key() {
            return key;
        }

        public long predictedBytes() {
            return 0L;
        }

        public abstract boolean step(int cellBudget);

        public abstract ViewPlate<B> result();
    }

    public static <B, M, V extends ProjectionContentView<B, M>> ViewPlate<B> build(Request<B, M, V> request) {
        Job<B, Object> job = job(request);
        while (!job.step(Integer.MAX_VALUE)) {
        }
        return job.result();
    }

    public static <B, M, W, V extends ProjectionContentView<B, M>> Job<B, W> job(Request<B, M, V> request) {
        return new BuildJob<B, M, W, V>(request, null, null);
    }

    public static <B, M, W, V extends ProjectionContentView<B, M>> Job<B, W> patch(Request<B, M, V> request, ViewPlate<B> previous,
                                                                                LongSet dirtyChunks) {
        return new BuildJob<B, M, W, V>(request, Objects.requireNonNull(previous, "previous"),
            new LongOpenHashSet(Objects.requireNonNull(dirtyChunks, "dirtyChunks")));
    }

    public static <B, M, V extends ProjectionContentView<B, M>> Footprint footprint(Request<B, M, V> request) {
        Geometry geometry = new Geometry(request);
        return geometry.footprint(request.buriedCellCulling() ? BURIED_PROBE_MARGIN : 0);
    }

    public static <B, M, V extends ProjectionContentView<B, M>> Footprint patchFootprint(Request<B, M, V> request, LongSet dirtyChunks) {
        Footprint full = footprint(request);
        int minChunkX = Integer.MAX_VALUE;
        int minChunkZ = Integer.MAX_VALUE;
        int maxChunkX = Integer.MIN_VALUE;
        int maxChunkZ = Integer.MIN_VALUE;
        for (long chunk : dirtyChunks) {
            int chunkX = (int) (chunk >> 32);
            int chunkZ = (int) chunk;
            minChunkX = Math.min(minChunkX, chunkX - 1);
            minChunkZ = Math.min(minChunkZ, chunkZ - 1);
            maxChunkX = Math.max(maxChunkX, chunkX + 1);
            maxChunkZ = Math.max(maxChunkZ, chunkZ + 1);
        }
        return new Footprint(Math.max(minChunkX, full.minChunkX()), Math.max(minChunkZ, full.minChunkZ()),
            Math.min(maxChunkX, full.maxChunkX()), Math.min(maxChunkZ, full.maxChunkZ()), full.predictedBytes());
    }

    private static final class Geometry {
        private final ProjectorFrameTransform transform;
        private final PortalFrame projectionLocalFrame;
        private final PortalFrame projectionRemoteFrame;
        private final int[] axisMin;
        private final int[] axisMax;
        private final int normalAxis;
        private final int rightAxis;
        private final int upAxis;
        private final int normalStep;
        private final int normalStart;
        private final int normalEnd;
        private final double clearance;
        private final double maxDepth;
        private final double facingNormal;
        private final double originNormal;
        private final PlateBox box;

        private Geometry(Request<?, ?, ?> request) {
            this.transform = new ProjectorFrameTransform();
            this.axisMin = new int[3];
            this.axisMax = new int[3];
            boolean frontSide = request.key().frontSide();
            PortalFrame localFrame = request.localFrame();
            this.projectionLocalFrame = localFrame.view(frontSide);
            this.projectionRemoteFrame = request.remoteFrame().view(frontSide);
            if (request.mirrorMode()) {
                transform.configureMirror(localFrame, request.mirrorRotationQuarterTurns(),
                    request.localOriginX(), request.localOriginY(), request.localOriginZ(), new double[3]);
            } else {
                transform.configure(projectionLocalFrame, projectionRemoteFrame,
                    request.localOriginX(), request.localOriginY(), request.localOriginZ(),
                    request.remoteOriginX(), request.remoteOriginY(), request.remoteOriginZ());
            }
            AxisAlignedBB area = request.aperture().getArea();
            this.clearance = ProjectorFrameTransform.portalPlaneClearance(area, localFrame);
            this.maxDepth = request.depthBlocks() + clearance;
            Direction normal = localFrame.getNormal();
            this.normalAxis = axisOf(normal);
            this.rightAxis = axisOf(projectionLocalFrame.getRight());
            this.upAxis = axisOf(projectionLocalFrame.getUp());
            this.facingNormal = component(normal, normalAxis);
            this.originNormal = component(normalAxis, request.localOriginX(), request.localOriginY(), request.localOriginZ());
            double signedMin = frontSide ? -maxDepth : clearance;
            double signedMax = frontSide ? -clearance : maxDepth;
            double centerA = originNormal + (signedMin / facingNormal);
            double centerB = originNormal + (signedMax / facingNormal);
            axisMin[normalAxis] = ProjectorFrameTransform.minBlockForCenter(Math.min(centerA, centerB));
            axisMax[normalAxis] = ProjectorFrameTransform.maxBlockForCenter(Math.max(centerA, centerB));
            double pad = Math.max(0.0D, request.lateralBlocks()) + Math.max(0.0D, request.aperturePadding());
            lateralBounds(area, rightAxis, pad);
            lateralBounds(area, upAxis, pad);
            boolean towardPositive = frontSide ? facingNormal < 0.0D : facingNormal > 0.0D;
            this.normalStep = towardPositive ? 1 : -1;
            this.normalStart = towardPositive ? axisMin[normalAxis] : axisMax[normalAxis];
            this.normalEnd = towardPositive ? axisMax[normalAxis] : axisMin[normalAxis];
            this.box = PlateBox.spanning(axisMin[0], axisMin[1], axisMin[2], axisMax[0], axisMax[1], axisMax[2]);
        }

        private boolean empty() {
            return box.cells() == 0L;
        }

        private Footprint footprint(int margin) {
            PlateBox remote = remoteBox(margin);
            if (remote.cells() == 0L) {
                return new Footprint(0, 0, -1, -1, ViewPlate.predictBytes(box));
            }
            return new Footprint(remote.minX() >> 4, remote.minZ() >> 4,
                (remote.minX() + remote.sizeX() - 1) >> 4, (remote.minZ() + remote.sizeZ() - 1) >> 4,
                ViewPlate.predictBytes(box));
        }

        private PlateBox remoteBox(int margin) {
            if (empty()) {
                return PlateBox.EMPTY;
            }
            double[] remote = new double[3];
            double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
            double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
            for (int corner = 0; corner < 8; corner++) {
                double x = ((corner & 1) == 0 ? axisMin[0] : axisMax[0]) + 0.5D;
                double y = ((corner & 2) == 0 ? axisMin[1] : axisMax[1]) + 0.5D;
                double z = ((corner & 4) == 0 ? axisMin[2] : axisMax[2]) + 0.5D;
                transform.apply(x, y, z, remote);
                for (int axis = 0; axis < 3; axis++) {
                    min[axis] = Math.min(min[axis], remote[axis]);
                    max[axis] = Math.max(max[axis], remote[axis]);
                }
            }
            return PlateBox.spanning(
                ((int) Math.floor(min[0])) - margin, ((int) Math.floor(min[1])) - margin, ((int) Math.floor(min[2])) - margin,
                ((int) Math.floor(max[0])) + margin, ((int) Math.floor(max[1])) + margin, ((int) Math.floor(max[2])) + margin);
        }

        private void lateralBounds(AxisAlignedBB area, int axis, double pad) {
            double areaMin = axis == 0 ? area.getXa() : axis == 1 ? area.getYa() : area.getZa();
            double areaMax = axis == 0 ? area.getXb() : axis == 1 ? area.getYb() : area.getZb();
            axisMin[axis] = ProjectorFrameTransform.minBlockForCenter(areaMin - pad);
            axisMax[axis] = ProjectorFrameTransform.maxBlockForCenter(areaMax + pad);
        }

        private static int axisOf(Direction direction) {
            return direction.x() != 0 ? 0 : direction.y() != 0 ? 1 : 2;
        }

        private static double component(Direction direction, int axis) {
            return axis == 0 ? direction.x() : axis == 1 ? direction.y() : direction.z();
        }

        private static double component(int axis, double x, double y, double z) {
            return axis == 0 ? x : axis == 1 ? y : z;
        }
    }

    private static final class BuildJob<B, M, W, V extends ProjectionContentView<B, M>> extends Job<B, W> {
        private final Request<B, M, V> request;
        private final V view;
        private final Geometry geometry;
        private final PlateOcclusionField<B, M> occlusion;
        private final Object2ObjectOpenHashMap<B, B> transformed;
        private final PlateGrid.Writer<B> grid;
        private final LongOpenHashSet dirtyChunks;
        private final double[] scratchRot;
        private final double[] scratchRemote;
        private final int[] cellCoords;
        private int n;
        private int r;
        private int u;
        private int slabIndex;
        private boolean started;
        private boolean done;
        private int minChunkX = Integer.MAX_VALUE;
        private int minChunkZ = Integer.MAX_VALUE;
        private int maxChunkX = Integer.MIN_VALUE;
        private int maxChunkZ = Integer.MIN_VALUE;
        private ViewPlate<B> result;

        private BuildJob(Request<B, M, V> request, ViewPlate<B> previous, LongOpenHashSet dirtyChunks) {
            super(request.key());
            this.request = request;
            this.view = request.destView();
            this.geometry = new Geometry(request);
            this.occlusion = new PlateOcclusionField<B, M>(view, request.blocks(),
                request.buriedCellCulling() ? geometry.remoteBox(BURIED_PROBE_MARGIN) : PlateBox.EMPTY);
            this.transformed = new Object2ObjectOpenHashMap<B, B>(64);
            boolean patching = previous != null && previous.grid().box().equals(geometry.box);
            this.grid = patching ? new PlateGrid.Writer<B>(previous.grid()) : new PlateGrid.Writer<B>(geometry.box);
            this.dirtyChunks = patching ? dirtyChunks : null;
            if (patching && previous.minChunkX() <= previous.maxChunkX()) {
                noteChunk(previous.minChunkX(), previous.minChunkZ());
                noteChunk(previous.maxChunkX(), previous.maxChunkZ());
            }
            this.scratchRot = new double[3];
            this.scratchRemote = new double[3];
            this.cellCoords = new int[3];
        }

        @Override
        public long predictedBytes() {
            return ViewPlate.predictBytes(geometry.box);
        }

        @Override
        public boolean step(int cellBudget) {
            if (done) {
                return true;
            }
            if (!started) {
                started = true;
                n = geometry.normalStart;
                r = geometry.axisMin[geometry.rightAxis];
                u = geometry.axisMin[geometry.upAxis];
                if (geometry.empty() || !scanContinues(n, geometry.normalEnd, geometry.normalStep)) {
                    finish();
                    return true;
                }
            }
            int remaining = Math.max(1, cellBudget);
            while (remaining > 0) {
                processCell();
                remaining--;
                if (!advance()) {
                    finish();
                    return true;
                }
            }
            return false;
        }

        @Override
        public ViewPlate<B> result() {
            return result;
        }

        private boolean advance() {
            u++;
            if (u <= geometry.axisMax[geometry.upAxis]) {
                return true;
            }
            u = geometry.axisMin[geometry.upAxis];
            r++;
            if (r <= geometry.axisMax[geometry.rightAxis]) {
                return true;
            }
            r = geometry.axisMin[geometry.rightAxis];
            n += geometry.normalStep;
            return scanContinues(n, geometry.normalEnd, geometry.normalStep);
        }

        private void processCell() {
            cellCoords[geometry.normalAxis] = n;
            cellCoords[geometry.rightAxis] = r;
            cellCoords[geometry.upAxis] = u;
            int x = cellCoords[0];
            int y = cellCoords[1];
            int z = cellCoords[2];
            double cellDot = geometry.facingNormal * ((n + 0.5D) - geometry.originNormal);
            if (!ProjectorFrameTransform.projectsBehindPortalPlane(cellDot, request.key().frontSide(), geometry.clearance)
                || Math.abs(cellDot) > geometry.maxDepth) {
                return;
            }
            slabIndex = LodPolicy.depthIndex(cellDot, geometry.clearance);
            long localKey = ProjectionCellKey.pack(x, y, z);
            int index = grid.index(x, y, z);
            LodPolicy lod = request.lod();
            boolean merged = lod.mergesSlab(slabIndex);
            if (merged && copyPreviousSlab(index, localKey)) {
                return;
            }
            geometry.transform.apply(x + 0.5D, y + 0.5D, z + 0.5D, scratchRemote);
            int rx = (int) Math.floor(scratchRemote[0]);
            int ry = (int) Math.floor(scratchRemote[1]);
            int rz = (int) Math.floor(scratchRemote[2]);
            if (dirtyChunks != null) {
                if (!merged && !ViewPlate.touches(dirtyChunks, rx, rz)) {
                    return;
                }
                grid.clear(index, localKey);
            }
            B remote = view.sampleBlockData(rx, ry, rz);
            if (remote == null) {
                return;
            }
            noteChunk(rx >> 4, rz >> 4);
            classify(index, localKey, remote, rx, ry, rz, lod);
        }

        private boolean copyPreviousSlab(int index, long localKey) {
            cellCoords[geometry.normalAxis] = n - geometry.normalStep;
            int previousIndex = grid.index(cellCoords[0], cellCoords[1], cellCoords[2]);
            long previousKey = ProjectionCellKey.pack(cellCoords[0], cellCoords[1], cellCoords[2]);
            cellCoords[geometry.normalAxis] = n;
            if (previousIndex < 0 || !grid.present(previousIndex)) {
                return false;
            }
            grid.copy(previousIndex, previousKey, index, localKey);
            return true;
        }

        private void classify(int index, long localKey, B remote, int rx, int ry, int rz, LodPolicy lod) {
            if (request.blocks().isOccluded(remote)) {
                grid.put(index, localKey, ProjectorSample.Kind.OCCLUDED, remote, remote, null);
                return;
            }
            M material = request.blocks().material(remote);
            if (request.blocks().isAir(material) || lod.dropsDetail(slabIndex, request.blocks().materialName(material))) {
                grid.put(index, localKey, ProjectorSample.Kind.REMOTE_AIR, request.air(), request.air(), null);
                return;
            }
            int occlusionDepth = request.buriedCellCulling() ? occlusion.depth(rx, ry, rz, remote) : 0;
            ProjectorSample.Kind kind = switch (occlusionDepth) {
                case 1 -> ProjectorSample.Kind.BACKING_BLOCK;
                case 2 -> ProjectorSample.Kind.OCCLUDED;
                default -> ProjectorSample.Kind.BLOCK;
            };
            if (kind == ProjectorSample.Kind.OCCLUDED) {
                grid.put(index, localKey, kind, remote, remote, null);
                return;
            }
            B projected = transformBlockData(remote);
            BlockEntitySample blockEntity = null;
            if (request.blockEntities() && request.blocks().blockEntityCandidate(material)) {
                blockEntity = view.sampleBlockEntity(rx, ry, rz);
                if (blockEntity == null && !view.blockEntitiesComplete(rx, rz)) {
                    grid.clear(index, localKey);
                    return;
                }
            }
            grid.put(index, localKey, kind, remote, projected, blockEntity);
        }

        private B transformBlockData(B source) {
            if (!request.blocks().requiresTransform(source)) {
                return source;
            }
            B cached = transformed.get(source);
            if (cached != null) {
                return cached;
            }
            B projected = request.mirrorMode()
                ? request.blocks().transform(source, DirectionMapping.mirror(request.localFrame(), request.mirrorRotationQuarterTurns(), scratchRot))
                : request.blocks().transform(source, DirectionMapping.between(geometry.projectionRemoteFrame, geometry.projectionLocalFrame, scratchRot));
            if (transformed.size() >= TRANSFORM_CACHE_LIMIT) {
                transformed.clear();
            }
            transformed.put(source, projected);
            return projected;
        }

        private void noteChunk(int chunkX, int chunkZ) {
            minChunkX = Math.min(minChunkX, chunkX);
            maxChunkX = Math.max(maxChunkX, chunkX);
            minChunkZ = Math.min(minChunkZ, chunkZ);
            maxChunkZ = Math.max(maxChunkZ, chunkZ);
        }

        private void finish() {
            done = true;
            UUID worldId = view.worldId();
            boolean sampled = minChunkX != Integer.MAX_VALUE;
            PlateGrid<B> built = grid.finish();
            result = new ViewPlate<B>(request.key(), built, request.destinationRevision(), request.transformRevision(),
                worldId, request.trackerVersion(),
                sampled ? minChunkX : 0, sampled ? minChunkZ : 0, sampled ? maxChunkX : -1, sampled ? maxChunkZ : -1,
                ViewPlate.estimateBytes(built));
        }

        private static boolean scanContinues(int coordinate, int end, int step) {
            return step > 0 ? coordinate <= end : coordinate >= end;
        }
    }
}
