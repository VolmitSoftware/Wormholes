package art.arcane.optics.plate;

import art.arcane.optics.math.BlockBox;

import java.util.Objects;
import art.arcane.optics.stream.SectionBiomes;
import art.arcane.optics.aperture.CellAperture;
import art.arcane.optics.view.BlockStates;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.view.ContentView;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.scan.Sample;
import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.frame.ViewWindow;
import art.arcane.optics.internal.plate.PlateOcclusionField;
import art.arcane.optics.volume.LodPolicy;
import art.arcane.optics.volume.ApertureSlab;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;

/**
 * Samples the portal-scoped volume behind one face of a portal through the destination view and
 * produces an immutable {@link ViewPlate}. The build is resumable so it can be spread over several
 * steps; the classification of every cell matches {@code Sampler.resolve} without recursion,
 * so the per-observer scan can substitute plate cells for sampler calls.
 */
public final class ViewPlateBuilder {
    private static final int BURIED_PROBE_MARGIN = 2;

    private ViewPlateBuilder() {
    }

    public record Request<B, M, V extends ContentView<B, M>>(ViewPlateKey key,
                          CellAperture aperture,
                          V destView,
                          Frame localFrame,
                          Frame remoteFrame,
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
                          BlockStates<B, M> blocks) {
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
        private volatile boolean urgent;

        protected Job(ViewPlateKey key) {
            this.key = Objects.requireNonNull(key, "key");
        }

        public ViewPlateKey key() {
            return key;
        }

        public boolean urgent() {
            return urgent;
        }

        public void markUrgent() {
            urgent = true;
        }

        public long predictedBytes() {
            return 0L;
        }

        public abstract boolean step(int cellBudget);

        public abstract ViewPlate<B> result();
    }

    public static <B, M, V extends ContentView<B, M>> ViewPlate<B> build(Request<B, M, V> request) {
        Job<B, Object> job = job(request);
        while (!job.step(Integer.MAX_VALUE)) {
        }
        return job.result();
    }

    public static <B, M, W, V extends ContentView<B, M>> Job<B, W> job(Request<B, M, V> request) {
        return new BuildJob<B, M, W, V>(request, null, null, null);
    }

    public static <B, M, W, V extends ContentView<B, M>> Job<B, W> patch(Request<B, M, V> request, ViewPlate<B> previous,
                                                                                LongSet dirtyChunks) {
        return new BuildJob<B, M, W, V>(request, Objects.requireNonNull(previous, "previous"),
            new LongOpenHashSet(Objects.requireNonNull(dirtyChunks, "dirtyChunks")), null);
    }

    public static <B, M, V extends ContentView<B, M>> Footprint footprint(Request<B, M, V> request) {
        Geometry geometry = new Geometry(request, null);
        return geometry.footprint(request.buriedCellCulling() ? BURIED_PROBE_MARGIN : 0);
    }

    public static <B, M, W, V extends ContentView<B, M>> Job<B, W> sectionJob(Request<B, M, V> request, BlockBox clip) {
        requireSection(clip);
        return new BuildJob<B, M, W, V>(request, null, null, clip);
    }

    public static <B, M, V extends ContentView<B, M>> Footprint sectionFootprint(Request<B, M, V> request, BlockBox clip) {
        requireSection(clip);
        BlockBox remote = sectionDestinationBox(request, clip);
        return new Footprint(remote.minX() >> 4, remote.minZ() >> 4, (remote.minX() + remote.sizeX() - 1) >> 4,
            (remote.minZ() + remote.sizeZ() - 1) >> 4, ViewPlate.predictBytes(clip) + 8192);
    }

    public static <B, M, V extends ContentView<B, M>> BlockBox sectionDestinationBox(Request<B, M, V> request, BlockBox clip) {
        requireSection(clip);
        return new Geometry(request, clip).remoteBox(clip, SectionBiomes.PADDING);
    }

    private static void requireSection(BlockBox clip) {
        Objects.requireNonNull(clip, "clip");
        if (clip.sizeX() > 16 || clip.sizeY() > 16 || clip.sizeZ() > 16) {
            throw new IllegalArgumentException("section clip exceeds 16 blocks: " + clip);
        }
    }

    public static <B, M, V extends ContentView<B, M>> Footprint patchFootprint(Request<B, M, V> request, LongSet dirtyChunks) {
        Footprint full = footprint(request);
        int minChunkX = Integer.MAX_VALUE;
        int minChunkZ = Integer.MAX_VALUE;
        int maxChunkX = Integer.MIN_VALUE;
        int maxChunkZ = Integer.MIN_VALUE;
        for (long chunk : dirtyChunks) {
            int chunkX = CellKeys.chunkX(chunk);
            int chunkZ = CellKeys.chunkZ(chunk);
            minChunkX = Math.min(minChunkX, chunkX - 1);
            minChunkZ = Math.min(minChunkZ, chunkZ - 1);
            maxChunkX = Math.max(maxChunkX, chunkX + 1);
            maxChunkZ = Math.max(maxChunkZ, chunkZ + 1);
        }
        return new Footprint(Math.max(minChunkX, full.minChunkX()), Math.max(minChunkZ, full.minChunkZ()),
            Math.min(maxChunkX, full.maxChunkX()), Math.min(maxChunkZ, full.maxChunkZ()), full.predictedBytes());
    }

    private static final class Geometry {
        private final OpticTransform transform;
        private final Frame projectionLocalFrame;
        private final Frame projectionRemoteFrame;
        private final int[] axisMin;
        private final int[] axisMax;
        private final int normalAxis;
        private final int rightAxis;
        private final int upAxis;
        private final int normalStep;
        private final int normalStart;
        private final int normalEnd;
        private final ApertureSlab volume;
        private final BlockBox box;

        private Geometry(Request<?, ?, ?> request, BlockBox clip) {
            this.axisMin = new int[3];
            this.axisMax = new int[3];
            boolean frontSide = request.key().frontSide();
            Frame localFrame = request.localFrame();
            this.projectionLocalFrame = localFrame.view(frontSide);
            this.projectionRemoteFrame = request.remoteFrame().view(frontSide);
            this.transform = ViewWindow.of(request.mirrorMode(), QuarterTurn.of(request.mirrorRotationQuarterTurns()),
                new Vec3d(request.localOriginX(), request.localOriginY(), request.localOriginZ()), localFrame,
                new Vec3d(request.remoteOriginX(), request.remoteOriginY(), request.remoteOriginZ()), request.remoteFrame(), frontSide, 0.0D).toward();
            double pad = Math.max(0.0D, request.lateralBlocks()) + Math.max(0.0D, request.aperturePadding());
            this.volume = ApertureSlab.of(request.aperture().getArea(), localFrame,
                ApertureSlab.plane(localFrame, request.localOriginX(), request.localOriginY(), request.localOriginZ()), frontSide,
                request.depthBlocks(), pad);
            this.normalAxis = volume.normalAxis();
            this.rightAxis = projectionLocalFrame.getRight().axisIndex();
            this.upAxis = projectionLocalFrame.getUp().axisIndex();
            for (int axis = 0; axis < 3; axis++) {
                axisMin[axis] = volume.min(axis);
                axisMax[axis] = volume.max(axis);
            }
            boolean towardPositive = volume.farStep() > 0;
            if (clip != null) {
                int planeBlock = (int) Math.floor(volume.plane());
                if (towardPositive) {
                    axisMin[normalAxis] = planeBlock;
                } else {
                    axisMax[normalAxis] = planeBlock;
                }
                axisMin[0] = Math.max(axisMin[0], clip.minX());
                axisMin[1] = Math.max(axisMin[1], clip.minY());
                axisMin[2] = Math.max(axisMin[2], clip.minZ());
                axisMax[0] = Math.min(axisMax[0], clip.minX() + clip.sizeX() - 1);
                axisMax[1] = Math.min(axisMax[1], clip.minY() + clip.sizeY() - 1);
                axisMax[2] = Math.min(axisMax[2], clip.minZ() + clip.sizeZ() - 1);
            }
            this.normalStep = volume.farStep();
            this.normalStart = towardPositive ? axisMin[normalAxis] : axisMax[normalAxis];
            this.normalEnd = towardPositive ? axisMax[normalAxis] : axisMin[normalAxis];
            this.box = BlockBox.spanning(axisMin[0], axisMin[1], axisMin[2], axisMax[0], axisMax[1], axisMax[2]);
        }

        private boolean empty() {
            return box.cells() == 0L;
        }

        private Footprint footprint(int margin) {
            BlockBox remote = remoteBox(margin);
            if (remote.cells() == 0L) {
                return new Footprint(0, 0, -1, -1, ViewPlate.predictBytes(box));
            }
            return new Footprint(remote.minX() >> 4, remote.minZ() >> 4,
                (remote.minX() + remote.sizeX() - 1) >> 4, (remote.minZ() + remote.sizeZ() - 1) >> 4,
                ViewPlate.predictBytes(box));
        }

        private BlockBox remoteBox(int margin) {
            return remoteBox(box, margin);
        }

        private BlockBox remoteBox(BlockBox source, int margin) {
            return transform.box(source, margin);
        }
    }

    private static final class BuildJob<B, M, W, V extends ContentView<B, M>> extends Job<B, W> {
        private final Request<B, M, V> request;
        private final V view;
        private final Geometry geometry;
        private final PlateOcclusionField<B, M> occlusion;
        private final AxisPermutation permutation;
        private final PlateGrid.Writer<B> grid;
        private final BlockBox section;
        private final LongOpenHashSet dirtyChunks;
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
        private PlateEnvironment environment;

        private BuildJob(Request<B, M, V> request, ViewPlate<B> previous, LongOpenHashSet dirtyChunks, BlockBox clip) {
            super(request.key());
            this.request = request;
            this.section = clip;
            this.view = request.destView();
            this.geometry = new Geometry(request, clip);
            this.occlusion = new PlateOcclusionField<B, M>(view, request.blocks(),
                request.buriedCellCulling() ? geometry.remoteBox(BURIED_PROBE_MARGIN) : BlockBox.EMPTY);
            this.permutation = geometry.transform.permutation().inverse();
            boolean patching = previous != null && previous.grid().box().equals(geometry.box);
            this.grid = patching ? new PlateGrid.Writer<B>(previous.grid()) : new PlateGrid.Writer<B>(geometry.box);
            this.dirtyChunks = patching ? dirtyChunks : null;
            if (patching && previous.minChunkX() <= previous.maxChunkX()) {
                noteChunk(previous.minChunkX(), previous.minChunkZ());
                noteChunk(previous.maxChunkX(), previous.maxChunkZ());
            }
            this.scratchRemote = new double[3];
            this.cellCoords = new int[3];
        }

        @Override
        public long predictedBytes() {
            return ViewPlate.predictBytes(geometry.box) + (section == null ? 0 : 8192);
        }

        @Override
        public boolean step(int cellBudget) {
            if (done) {
                return true;
            }
            if (!started) {
                if (section != null) {
                    environment = PlateEnvironment.capture(section, geometry.transform, view);
                    if (environment == null) {
                        return false;
                    }
                }
                started = true;
                n = geometry.normalStart;
                r = geometry.axisMin[geometry.rightAxis];
                u = geometry.axisMin[geometry.upAxis];
                if (geometry.empty() || !scanContinues(n, geometry.normalEnd, geometry.normalStep)) {
                    finish();
                    return true;
                }
                if (section != null && fullyWithinDepth() && view.isEmpty(geometry.remoteBox(0))) {
                    Footprint footprint = geometry.footprint(0);
                    noteChunk(footprint.minChunkX(), footprint.minChunkZ());
                    noteChunk(footprint.maxChunkX(), footprint.maxChunkZ());
                    grid.fillAir(request.air());
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

        private boolean fullyWithinDepth() {
            return includesSlab(geometry.normalStart) && includesSlab(geometry.normalEnd);
        }

        private boolean includesSlab(int normalCoordinate) {
            return section != null ? geometry.volume.withinDepth(normalCoordinate) : geometry.volume.containsSlab(normalCoordinate);
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
            if (!includesSlab(n)) {
                return;
            }
            slabIndex = geometry.volume.depthIndex(n);
            long localKey = CellKeys.pack(x, y, z);
            int index = grid.index(x, y, z);
            LodPolicy lod = request.lod();
            boolean merged = lod.mergesSlab(slabIndex);
            if (merged && copyPreviousSlab(index, localKey)) {
                return;
            }
            geometry.transform.snappedPointInto(x + 0.5D, y + 0.5D, z + 0.5D, scratchRemote);
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
            long previousKey = CellKeys.pack(cellCoords[0], cellCoords[1], cellCoords[2]);
            cellCoords[geometry.normalAxis] = n;
            if (previousIndex < 0 || !grid.present(previousIndex)) {
                return false;
            }
            grid.copy(previousIndex, previousKey, index, localKey);
            return true;
        }

        private void classify(int index, long localKey, B remote, int rx, int ry, int rz, LodPolicy lod) {
            if (request.blocks().isOccluded(remote)) {
                grid.put(index, localKey, Sample.Kind.OCCLUDED, remote, remote, null);
                return;
            }
            M material = request.blocks().material(remote);
            if (request.blocks().isAir(material) || lod.dropsDetail(slabIndex, request.blocks().materialName(material))) {
                grid.put(index, localKey, Sample.Kind.REMOTE_AIR, request.air(), request.air(), null);
                return;
            }
            int occlusionDepth = request.buriedCellCulling() ? occlusion.depth(rx, ry, rz, remote) : 0;
            Sample.Kind kind = switch (occlusionDepth) {
                case 1 -> Sample.Kind.BACKING_BLOCK;
                case 2 -> Sample.Kind.OCCLUDED;
                default -> Sample.Kind.BLOCK;
            };
            if (kind == Sample.Kind.OCCLUDED) {
                grid.put(index, localKey, kind, remote, remote, null);
                return;
            }
            B projected = section != null ? remote : transformBlockData(remote);
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
            return request.blocks().requiresTransform(source) ? request.blocks().transform(source, permutation) : source;
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
            if (section != null) {
                BlockBox metadata = geometry.remoteBox(section, SectionBiomes.PADDING);
                noteChunk(metadata.minX() >> 4, metadata.minZ() >> 4);
                noteChunk((metadata.minX() + metadata.sizeX() - 1) >> 4, (metadata.minZ() + metadata.sizeZ() - 1) >> 4);
            }
            boolean sampled = minChunkX != Integer.MAX_VALUE;
            PlateGrid<B> built = grid.finish();
            result = new ViewPlate<B>(request.key(), built, request.destinationRevision(), request.transformRevision(),
                worldId, request.trackerVersion(),
                sampled ? minChunkX : 0, sampled ? minChunkZ : 0, sampled ? maxChunkX : -1, sampled ? maxChunkZ : -1,
                ViewPlate.estimateBytes(built), environment);
        }

        private static boolean scanContinues(int coordinate, int end, int step) {
            return step > 0 ? coordinate <= end : coordinate >= end;
        }
    }
}
