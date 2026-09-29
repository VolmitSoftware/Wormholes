package art.arcane.wormholes.render;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.HashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;

import art.arcane.wormholes.render.view.ProjectionMaterialView;
import java.util.function.Supplier;

import art.arcane.wormholes.util.AxisAlignedBB;

public final class ProjectorSampleMemo<B, M, V extends ProjectionMaterialView<B, M>> {

    private static final int MIN_BUDGET = 4096;
    private static final int BUDGET_FACTOR = 3;

    private final HashMap<V, Long2ObjectOpenHashMap<ProjectorSample<B, V>>> remoteSamples;
    private final HashMap<V, Long2ByteOpenHashMap> occlusion;
    private final Long2ByteOpenHashMap localAir;
    private final Long2ByteOpenHashMap localOccupancy;
    private final ProjectionBlockTypes<B, M> blocks;
    private final Supplier<ProjectionWorldChangeTracker> changeTracker;
    private V lastSampleView;
    private Long2ObjectOpenHashMap<ProjectorSample<B, V>> lastSampleMap;
    private V lastOcclusionView;
    private Long2ByteOpenHashMap lastOcclusionMap;
    private long destinationVersion;
    private long destinationRevision;
    private long localRevision;
    private long localChangeVersion;
    private boolean hasLocalRegionRect;
    private int localRegionChunkMinX;
    private int localRegionChunkMinZ;
    private int localRegionChunkMaxX;
    private int localRegionChunkMaxZ;

    public ProjectorSampleMemo(ProjectionBlockTypes<B, M> blocks, Supplier<ProjectionWorldChangeTracker> changeTracker) {
        this.remoteSamples = new HashMap<V, Long2ObjectOpenHashMap<ProjectorSample<B, V>>>(4);
        this.occlusion = new HashMap<V, Long2ByteOpenHashMap>(4);
        this.localAir = new Long2ByteOpenHashMap(1024);
        this.localOccupancy = new Long2ByteOpenHashMap(256);
        this.blocks = Objects.requireNonNull(blocks);
        this.changeTracker = Objects.requireNonNull(changeTracker);
        this.destinationVersion = -1L;
        this.destinationRevision = Long.MIN_VALUE;
        this.localRevision = Long.MIN_VALUE;
        this.localChangeVersion = -1L;
        this.hasLocalRegionRect = false;
    }

    public ProjectionBlockTypes<B, M> blocks() {
        return blocks;
    }

    public static int budgetFor(int lastRenderedCells, long fittedCandidateWork) {
        long renderedBudget = (long) Math.max(0, lastRenderedCells) * BUDGET_FACTOR;
        long candidateWork = Math.max(0L, fittedCandidateWork);
        long candidateBudget = candidateWork >= Integer.MAX_VALUE
            ? Integer.MAX_VALUE : Math.min(Integer.MAX_VALUE, candidateWork + (candidateWork / 2L));
        return (int) Math.min(Integer.MAX_VALUE,
            Math.max(MIN_BUDGET, Math.max(renderedBudget, candidateBudget)));
    }

    public ProjectorSample<B, V> cachedSample(V view, int x, int y, int z) {
        return sampleMapFor(view).get(ProjectionCellKey.pack(x, y, z));
    }

    public void cacheSample(V view, int x, int y, int z, ProjectorSample<B, V> sample) {
        sampleMapFor(view).put(ProjectionCellKey.pack(x, y, z), sample);
    }

    private Long2ObjectOpenHashMap<ProjectorSample<B, V>> sampleMapFor(V view) {
        if (view == lastSampleView && lastSampleMap != null) {
            return lastSampleMap;
        }
        Long2ObjectOpenHashMap<ProjectorSample<B, V>> viewSamples = remoteSamples.get(view);
        if (viewSamples == null) {
            viewSamples = new Long2ObjectOpenHashMap<ProjectorSample<B, V>>(256);
            remoteSamples.put(view, viewSamples);
        }
        lastSampleView = view;
        lastSampleMap = viewSamples;
        return viewSamples;
    }

    public boolean isLocalAir(V view, int x, int y, int z) {
        long key = ProjectionCellKey.pack(x, y, z);
        byte known = localAir.get(key);
        if (known != 0) {
            return known == 1;
        }
        M material = view.sampleMaterial(x, y, z);
        boolean air = material != null && blocks.isAir(material);
        localAir.put(key, air ? (byte) 1 : (byte) 2);
        return air;
    }

    public ProjectorHoldProof.Occupancy localOccupancy(V view, int x, int y, int z) {
        long key = ProjectionCellKey.pack(x, y, z);
        byte known = localOccupancy.get(key);
        if (known != 0) {
            return known == 1 ? ProjectorHoldProof.Occupancy.OCCLUDING : ProjectorHoldProof.Occupancy.OPEN;
        }
        M material = view.sampleMaterial(x, y, z);
        if (material == null) {
            return ProjectorHoldProof.Occupancy.UNKNOWN;
        }
        if (!blocks.isOccluding(material)) {
            localOccupancy.put(key, (byte) 2);
            return ProjectorHoldProof.Occupancy.OPEN;
        }
        localOccupancy.put(key, (byte) 1);
        includeLocalChunk(x >> 4, z >> 4);
        return ProjectorHoldProof.Occupancy.OCCLUDING;
    }

    private void includeLocalChunk(int chunkX, int chunkZ) {
        if (!hasLocalRegionRect) {
            return;
        }
        localRegionChunkMinX = Math.min(localRegionChunkMinX, chunkX);
        localRegionChunkMaxX = Math.max(localRegionChunkMaxX, chunkX);
        localRegionChunkMinZ = Math.min(localRegionChunkMinZ, chunkZ);
        localRegionChunkMaxZ = Math.max(localRegionChunkMaxZ, chunkZ);
    }

    public int occlusionDepthInView(V view, int x, int y, int z, B selfData) {
        if (selfData == null) {
            return 0;
        }
        if (!blocks.isOccluding(blocks.material(selfData))) {
            return 0;
        }
        Long2ByteOpenHashMap memo;
        if (view == lastOcclusionView && lastOcclusionMap != null) {
            memo = lastOcclusionMap;
        } else {
            memo = occlusion.get(view);
            if (memo == null) {
                memo = new Long2ByteOpenHashMap(1024);
                occlusion.put(view, memo);
            }
            lastOcclusionView = view;
            lastOcclusionMap = memo;
        }
        memo.put(ProjectionCellKey.pack(x, y, z), (byte) 1);
        if (!occludingMemoized(view, memo, x + 1, y, z)
            || !occludingMemoized(view, memo, x - 1, y, z)
            || !occludingMemoized(view, memo, x, y + 1, z)
            || !occludingMemoized(view, memo, x, y - 1, z)
            || !occludingMemoized(view, memo, x, y, z + 1)
            || !occludingMemoized(view, memo, x, y, z - 1)) {
            return 0;
        }
        if (!occludingMemoized(view, memo, x + 2, y, z)
            || !occludingMemoized(view, memo, x + 1, y + 1, z)
            || !occludingMemoized(view, memo, x + 1, y - 1, z)
            || !occludingMemoized(view, memo, x + 1, y, z + 1)
            || !occludingMemoized(view, memo, x + 1, y, z - 1)
            || !occludingMemoized(view, memo, x - 2, y, z)
            || !occludingMemoized(view, memo, x - 1, y + 1, z)
            || !occludingMemoized(view, memo, x - 1, y - 1, z)
            || !occludingMemoized(view, memo, x - 1, y, z + 1)
            || !occludingMemoized(view, memo, x - 1, y, z - 1)
            || !occludingMemoized(view, memo, x, y + 2, z)
            || !occludingMemoized(view, memo, x, y + 1, z + 1)
            || !occludingMemoized(view, memo, x, y + 1, z - 1)
            || !occludingMemoized(view, memo, x, y - 2, z)
            || !occludingMemoized(view, memo, x, y - 1, z + 1)
            || !occludingMemoized(view, memo, x, y - 1, z - 1)
            || !occludingMemoized(view, memo, x, y, z + 2)
            || !occludingMemoized(view, memo, x, y, z - 2)) {
            return 1;
        }
        return 2;
    }

    private boolean occludingMemoized(V view, Long2ByteOpenHashMap memo, int x, int y, int z) {
        long key = ProjectionCellKey.pack(x, y, z);
        byte known = memo.get(key);
        if (known != 0) {
            return known == 1;
        }
        boolean occluding = blocks.isOccluding(view.sampleMaterial(x, y, z));
        memo.put(key, occluding ? (byte) 1 : (byte) 2);
        return occluding;
    }

    public boolean destinationStale(long viewRevision, boolean hasDestinationWorld, LongPredicate dirtySince) {
        return destinationMemosStale(viewRevision, destinationRevision, hasDestinationWorld,
            () -> dirtySince.test(destinationVersion));
    }

    public boolean destinationOverBudget(int budget) {
        for (Long2ObjectOpenHashMap<ProjectorSample<B, V>> viewSamples : remoteSamples.values()) {
            if (viewSamples.size() > budget) {
                return true;
            }
        }
        for (Long2ByteOpenHashMap viewOcclusion : occlusion.values()) {
            if (viewOcclusion.size() > budget) {
                return true;
            }
        }
        return false;
    }

    public void clearDestinationSamples() {
        for (Long2ObjectOpenHashMap<ProjectorSample<B, V>> viewSamples : remoteSamples.values()) {
            viewSamples.clear();
        }
        for (Long2ByteOpenHashMap viewOcclusion : occlusion.values()) {
            viewOcclusion.clear();
        }
    }

    public void refreshDestination(long viewRevision) {
        destinationRevision = viewRevision;
        ProjectionWorldChangeTracker tracker = changeTracker.get();
        if (tracker != null) {
            destinationVersion = tracker.currentVersion();
        }
    }

    public boolean refreshLocal(boolean forceStableCellResample, boolean localDirty, long viewRevision, int budget) {
        if (localSampleMemoStale(forceStableCellResample, localDirty, viewRevision, localRevision, localAir.size(), budget)) {
            localAir.clear();
            localOccupancy.clear();
            localRevision = viewRevision;
            hasLocalRegionRect = false;
            return true;
        }
        return false;
    }

    public void markLocalScanned() {
        ProjectionWorldChangeTracker tracker = changeTracker.get();
        if (tracker != null) {
            localChangeVersion = tracker.currentVersion();
        }
    }

    public boolean localRegionDirty(UUID localWorldId) {
        ProjectionWorldChangeTracker tracker = changeTracker.get();
        if (tracker == null || localWorldId == null || !hasLocalRegionRect) {
            return true;
        }
        return tracker.dirtySince(localWorldId, localRegionChunkMinX, localRegionChunkMinZ,
            localRegionChunkMaxX, localRegionChunkMaxZ, localChangeVersion);
    }

    public void expandLocalRegionRect(AxisAlignedBB region) {
        int minChunkX = (((int) Math.floor(region.getXa())) >> 4) - 1;
        int maxChunkX = (((int) Math.floor(region.getXb())) >> 4) + 1;
        int minChunkZ = (((int) Math.floor(region.getZa())) >> 4) - 1;
        int maxChunkZ = (((int) Math.floor(region.getZb())) >> 4) + 1;
        if (!hasLocalRegionRect) {
            localRegionChunkMinX = minChunkX;
            localRegionChunkMaxX = maxChunkX;
            localRegionChunkMinZ = minChunkZ;
            localRegionChunkMaxZ = maxChunkZ;
            hasLocalRegionRect = true;
            return;
        }
        localRegionChunkMinX = Math.min(localRegionChunkMinX, minChunkX);
        localRegionChunkMaxX = Math.max(localRegionChunkMaxX, maxChunkX);
        localRegionChunkMinZ = Math.min(localRegionChunkMinZ, minChunkZ);
        localRegionChunkMaxZ = Math.max(localRegionChunkMaxZ, maxChunkZ);
    }

    public void discard() {
        destinationVersion = -1L;
        destinationRevision = Long.MIN_VALUE;
        localRevision = Long.MIN_VALUE;
        remoteSamples.clear();
        occlusion.clear();
        localAir.clear();
        localOccupancy.clear();
        hasLocalRegionRect = false;
        lastSampleView = null;
        lastSampleMap = null;
        lastOcclusionView = null;
        lastOcclusionMap = null;
    }


    public static boolean localSampleMemoStale(boolean forceStableCellResample,
                                        boolean localDirty,
                                        long viewRevision,
                                        long memoRevision,
                                        int memoSize,
                                        int memoBudget) {
        return forceStableCellResample || localDirty || viewRevision != memoRevision || memoSize > memoBudget;
    }

    public static boolean destinationMemosStale(long viewRevision,
                                         long memoRevision,
                                         boolean hasDestinationWorld,
                                         BooleanSupplier destinationDirty) {
        if (viewRevision != memoRevision) {
            return true;
        }
        if (!hasDestinationWorld) {
            return false;
        }
        return destinationDirty.getAsBoolean();
    }
}
