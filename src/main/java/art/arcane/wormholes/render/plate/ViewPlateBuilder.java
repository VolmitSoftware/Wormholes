package art.arcane.wormholes.render.plate;

import java.util.Objects;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.render.PortalProjector;
import art.arcane.wormholes.render.ProjectedBlockDataTransformer;
import art.arcane.wormholes.render.ProjectionCellKey;
import art.arcane.wormholes.render.ProjectorFrameTransform;
import art.arcane.wormholes.render.ProjectorSample;
import art.arcane.wormholes.render.ProjectorSampleMemo;
import art.arcane.wormholes.render.blockentity.BlockEntityMaterials;
import art.arcane.wormholes.render.blockentity.BlockEntitySample;
import art.arcane.wormholes.render.lod.LodPolicy;
import art.arcane.wormholes.render.view.OccludedMarker;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

/**
 * Samples the portal-scoped volume behind one face of a portal through the destination view and
 * produces an immutable {@link ViewPlate}. The build is resumable so the Paper path can spread it over
 * several region ticks; the classification of every cell matches {@code ProjectorSampler.resolve}
 * without recursion, so the per-observer scan can substitute plate cells for sampler calls.
 */
public final class ViewPlateBuilder {
    private static final int TRANSFORM_CACHE_LIMIT = 4096;

    private ViewPlateBuilder() {
    }

    public record Request(ViewPlateKey key,
                          ILocalPortal portal,
                          ProjectionWorldView destView,
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
                          BlockData air,
                          LodPolicy lod,
                          boolean blockEntities,
                          long destinationRevision,
                          long transformRevision,
                          long trackerVersion,
                          ProjectorSampleMemo.MaterialOcclusion occlusion) {
        public Request {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(portal, "portal");
            Objects.requireNonNull(destView, "destView");
            Objects.requireNonNull(localFrame, "localFrame");
            Objects.requireNonNull(remoteFrame, "remoteFrame");
            Objects.requireNonNull(air, "air");
            lod = lod == null ? LodPolicy.NONE : lod;
        }
    }

    /** Where a build may run: off-thread for snapshot and remote views, otherwise on the destination region thread. */
    public record Execution(boolean offThread, World world, int chunkX, int chunkZ) {
        public static Execution async() {
            return new Execution(true, null, 0, 0);
        }

        public static Execution region(World world, int chunkX, int chunkZ) {
            return new Execution(false, Objects.requireNonNull(world, "world"), chunkX, chunkZ);
        }
    }

    public abstract static class Job {
        private final ViewPlateKey key;
        private final Execution execution;

        protected Job(ViewPlateKey key) {
            this(key, Execution.async());
        }

        protected Job(ViewPlateKey key, Execution execution) {
            this.key = Objects.requireNonNull(key, "key");
            this.execution = Objects.requireNonNull(execution, "execution");
        }

        public ViewPlateKey key() {
            return key;
        }

        public Execution execution() {
            return execution;
        }

        /** Advances the build by up to {@code cellBudget} cells; returns true once the plate is complete. */
        public abstract boolean step(int cellBudget);

        public abstract ViewPlate result();
    }

    public static ViewPlate build(Request request) {
        Job job = job(request);
        while (!job.step(Integer.MAX_VALUE)) {
        }
        return job.result();
    }

    public static Job job(Request request) {
        return new BuildJob(request, Execution.async());
    }

    public static Job job(Request request, Execution execution) {
        return new BuildJob(request, execution);
    }

    private static final class BuildJob extends Job {
        private final Request request;
        private final ProjectionWorldView view;
        private final ProjectorFrameTransform transform;
        private final ProjectorSampleMemo memo;
        private final Object2ObjectOpenHashMap<BlockData, BlockData> transformed;
        private final Long2ObjectOpenHashMap<PlateCell> cells;
        private final Long2ObjectOpenHashMap<PlateCell> previousSlab;
        private final Long2ObjectOpenHashMap<PlateCell> currentSlab;
        private final double[] scratchRot;
        private final double[] scratchRemote;
        private final int[] axisMin;
        private final int[] axisMax;
        private final int[] cellCoords;
        private final PortalFrame projectionLocalFrame;
        private final PortalFrame projectionRemoteFrame;
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
        private ViewPlate result;

        private BuildJob(Request request, Execution execution) {
            super(request.key(), execution);
            this.request = request;
            this.view = request.destView();
            this.transform = new ProjectorFrameTransform();
            this.memo = request.occlusion() == null ? new ProjectorSampleMemo() : new ProjectorSampleMemo(request.occlusion());
            this.transformed = new Object2ObjectOpenHashMap<BlockData, BlockData>(64);
            this.cells = new Long2ObjectOpenHashMap<PlateCell>(1024);
            this.previousSlab = new Long2ObjectOpenHashMap<PlateCell>(256);
            this.currentSlab = new Long2ObjectOpenHashMap<PlateCell>(256);
            this.scratchRot = new double[3];
            this.scratchRemote = new double[3];
            this.axisMin = new int[3];
            this.axisMax = new int[3];
            this.cellCoords = new int[3];

            boolean frontSide = request.key().frontSide();
            PortalFrame localFrame = request.localFrame();
            this.projectionLocalFrame = localFrame.view(frontSide);
            this.projectionRemoteFrame = request.remoteFrame().view(frontSide);
            if (request.mirrorMode()) {
                transform.configureMirror(localFrame, request.mirrorRotationQuarterTurns(),
                    request.localOriginX(), request.localOriginY(), request.localOriginZ(), scratchRot);
            } else {
                transform.configure(projectionLocalFrame, projectionRemoteFrame,
                    request.localOriginX(), request.localOriginY(), request.localOriginZ(),
                    request.remoteOriginX(), request.remoteOriginY(), request.remoteOriginZ());
            }
            AxisAlignedBB area = request.portal().getStructure().getArea();
            this.clearance = PortalProjector.portalPlaneClearance(area, localFrame);
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
            axisMin[normalAxis] = PortalProjector.minBlockForCenter(Math.min(centerA, centerB));
            axisMax[normalAxis] = PortalProjector.maxBlockForCenter(Math.max(centerA, centerB));
            double pad = Math.max(0.0D, request.lateralBlocks()) + Math.max(0.0D, request.aperturePadding());
            lateralBounds(area, rightAxis, pad);
            lateralBounds(area, upAxis, pad);
            boolean towardPositive = frontSide ? facingNormal < 0.0D : facingNormal > 0.0D;
            this.normalStep = towardPositive ? 1 : -1;
            this.normalStart = towardPositive ? axisMin[normalAxis] : axisMax[normalAxis];
            this.normalEnd = towardPositive ? axisMax[normalAxis] : axisMin[normalAxis];
        }

        private void lateralBounds(AxisAlignedBB area, int axis, double pad) {
            double areaMin = axis == 0 ? area.getXa() : axis == 1 ? area.getYa() : area.getZa();
            double areaMax = axis == 0 ? area.getXb() : axis == 1 ? area.getYb() : area.getZb();
            axisMin[axis] = PortalProjector.minBlockForCenter(areaMin - pad);
            axisMax[axis] = PortalProjector.maxBlockForCenter(areaMax + pad);
        }

        @Override
        public boolean step(int cellBudget) {
            if (done) {
                return true;
            }
            if (!started) {
                started = true;
                n = normalStart;
                r = axisMin[rightAxis];
                u = axisMin[upAxis];
                if (!scanContinues(n, normalEnd, normalStep)
                    || axisMin[rightAxis] > axisMax[rightAxis]
                    || axisMin[upAxis] > axisMax[upAxis]) {
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
        public ViewPlate result() {
            return result;
        }

        private boolean advance() {
            u++;
            if (u <= axisMax[upAxis]) {
                return true;
            }
            u = axisMin[upAxis];
            r++;
            if (r <= axisMax[rightAxis]) {
                return true;
            }
            r = axisMin[rightAxis];
            n += normalStep;
            previousSlab.clear();
            previousSlab.putAll(currentSlab);
            currentSlab.clear();
            return scanContinues(n, normalEnd, normalStep);
        }

        private void processCell() {
            cellCoords[normalAxis] = n;
            cellCoords[rightAxis] = r;
            cellCoords[upAxis] = u;
            int x = cellCoords[0];
            int y = cellCoords[1];
            int z = cellCoords[2];
            double cellDot = facingNormal * ((n + 0.5D) - originNormal);
            if (!PortalProjector.projectsBehindPortalPlane(cellDot, request.key().frontSide(), clearance)
                || Math.abs(cellDot) > maxDepth) {
                return;
            }
            slabIndex = LodPolicy.depthIndex(cellDot, clearance);
            long localKey = ProjectionCellKey.pack(x, y, z);
            long slabKey = ProjectionCellKey.pack(r, 0, u);
            LodPolicy lod = request.lod();
            if (lod.mergesSlab(slabIndex)) {
                PlateCell previous = previousSlab.get(slabKey);
                if (previous != null) {
                    cells.put(localKey, previous);
                    currentSlab.put(slabKey, previous);
                    return;
                }
            }
            transform.apply(x + 0.5D, y + 0.5D, z + 0.5D, scratchRemote);
            int rx = (int) Math.floor(scratchRemote[0]);
            int ry = (int) Math.floor(scratchRemote[1]);
            int rz = (int) Math.floor(scratchRemote[2]);
            BlockData remote = view.sampleBlockData(rx, ry, rz);
            if (remote == null) {
                return;
            }
            noteChunk(rx >> 4, rz >> 4);
            long remoteKey = ProjectionCellKey.pack(rx, ry, rz);
            PlateCell cell = classify(remote, rx, ry, rz, remoteKey, lod);
            cells.put(localKey, cell);
            currentSlab.put(slabKey, cell);
        }

        private PlateCell classify(BlockData remote, int rx, int ry, int rz, long remoteKey, LodPolicy lod) {
            if (OccludedMarker.isStandIn(remote)) {
                return new PlateCell(ProjectorSample.Kind.OCCLUDED, remote, remote, remoteKey, null);
            }
            Material material = remote.getMaterial();
            if (ProjectorSampleMemo.isAir(material) || lod.dropsDetail(slabIndex, material)) {
                return new PlateCell(ProjectorSample.Kind.REMOTE_AIR, request.air(), request.air(), remoteKey, null);
            }
            int occlusionDepth = request.buriedCellCulling() ? memo.occlusionDepthInView(view, rx, ry, rz, remote) : 0;
            ProjectorSample.Kind kind = switch (occlusionDepth) {
                case 1 -> ProjectorSample.Kind.BACKING_BLOCK;
                case 2 -> ProjectorSample.Kind.OCCLUDED;
                default -> ProjectorSample.Kind.BLOCK;
            };
            if (kind == ProjectorSample.Kind.OCCLUDED) {
                return new PlateCell(kind, remote, remote, remoteKey, null);
            }
            BlockData projected = transformBlockData(remote);
            BlockEntitySample blockEntity = null;
            if (request.blockEntities() && BlockEntityMaterials.isCandidate(material)) {
                blockEntity = view.sampleBlockEntity(rx, ry, rz);
            }
            return new PlateCell(kind, remote, projected, remoteKey, blockEntity);
        }

        private BlockData transformBlockData(BlockData source) {
            if (!ProjectedBlockDataTransformer.requiresTransform(source)) {
                return source;
            }
            BlockData cached = transformed.get(source);
            if (cached != null) {
                return cached;
            }
            BlockData projected = request.mirrorMode()
                ? ProjectedBlockDataTransformer.mirror(source, request.localFrame(), request.mirrorRotationQuarterTurns(), scratchRot)
                : ProjectedBlockDataTransformer.transform(source, projectionRemoteFrame, projectionLocalFrame, scratchRot);
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
            previousSlab.clear();
            currentSlab.clear();
            World world = view.getWorld();
            UUID worldId = world == null ? null : world.getUID();
            boolean sampled = minChunkX != Integer.MAX_VALUE;
            result = new ViewPlate(request.key(), cells, request.destinationRevision(), request.transformRevision(),
                worldId, request.trackerVersion(),
                sampled ? minChunkX : 0, sampled ? minChunkZ : 0, sampled ? maxChunkX : -1, sampled ? maxChunkZ : -1,
                ViewPlate.estimateBytes(cells));
        }

        private static boolean scanContinues(int coordinate, int end, int step) {
            return step > 0 ? coordinate <= end : coordinate >= end;
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
}
