package art.arcane.wormholes.render;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.HashMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.util.AxisAlignedBB;

final class ProjectorSampleMemo {
    private static final int MIN_BUDGET = 4096;
    private static final int BUDGET_FACTOR = 3;

    private final HashMap<ProjectionWorldView, Long2ObjectOpenHashMap<ProjectorSample>> remoteSamples;
    private final HashMap<ProjectionWorldView, Long2ByteOpenHashMap> occlusion;
    private final Long2ByteOpenHashMap localAir;
    private ProjectionWorldView lastSampleView;
    private Long2ObjectOpenHashMap<ProjectorSample> lastSampleMap;
    private ProjectionWorldView lastOcclusionView;
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

    ProjectorSampleMemo() {
        this.remoteSamples = new HashMap<ProjectionWorldView, Long2ObjectOpenHashMap<ProjectorSample>>(4);
        this.occlusion = new HashMap<ProjectionWorldView, Long2ByteOpenHashMap>(4);
        this.localAir = new Long2ByteOpenHashMap(1024);
        this.destinationVersion = -1L;
        this.destinationRevision = Long.MIN_VALUE;
        this.localRevision = Long.MIN_VALUE;
        this.localChangeVersion = -1L;
        this.hasLocalRegionRect = false;
    }

    static int budgetFor(int lastRenderedCells) {
        return Math.max(MIN_BUDGET, lastRenderedCells * BUDGET_FACTOR);
    }

    ProjectorSample cachedSample(ProjectionWorldView view, int x, int y, int z) {
        return sampleMapFor(view).get(ProjectionCellKey.pack(x, y, z));
    }

    void cacheSample(ProjectionWorldView view, int x, int y, int z, ProjectorSample sample) {
        sampleMapFor(view).put(ProjectionCellKey.pack(x, y, z), sample);
    }

    private Long2ObjectOpenHashMap<ProjectorSample> sampleMapFor(ProjectionWorldView view) {
        if (view == lastSampleView && lastSampleMap != null) {
            return lastSampleMap;
        }
        Long2ObjectOpenHashMap<ProjectorSample> viewSamples = remoteSamples.get(view);
        if (viewSamples == null) {
            viewSamples = new Long2ObjectOpenHashMap<ProjectorSample>(256);
            remoteSamples.put(view, viewSamples);
        }
        lastSampleView = view;
        lastSampleMap = viewSamples;
        return viewSamples;
    }

    boolean isLocalAir(ProjectionWorldView view, int x, int y, int z) {
        long key = ProjectionCellKey.pack(x, y, z);
        byte known = localAir.get(key);
        if (known != 0) {
            return known == 1;
        }
        Material material = view.sampleMaterial(x, y, z);
        boolean air = material != null && isAir(material);
        localAir.put(key, air ? (byte) 1 : (byte) 2);
        return air;
    }

    boolean buriedInView(ProjectionWorldView view, int x, int y, int z, BlockData selfData) {
        if (selfData == null) {
            return false;
        }
        Material selfMaterial = selfData.getMaterial();
        if (ProjectionWorldView.isAir(selfMaterial) || !selfMaterial.isOccluding()) {
            return false;
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
        return occludingMemoized(view, memo, x + 1, y, z)
            && occludingMemoized(view, memo, x - 1, y, z)
            && occludingMemoized(view, memo, x, y + 1, z)
            && occludingMemoized(view, memo, x, y - 1, z)
            && occludingMemoized(view, memo, x, y, z + 1)
            && occludingMemoized(view, memo, x, y, z - 1);
    }

    private static boolean occludingMemoized(ProjectionWorldView view, Long2ByteOpenHashMap memo, int x, int y, int z) {
        long key = ProjectionCellKey.pack(x, y, z);
        byte known = memo.get(key);
        if (known != 0) {
            return known == 1;
        }
        boolean occluding = occludingSample(view, x, y, z);
        memo.put(key, occluding ? (byte) 1 : (byte) 2);
        return occluding;
    }

    boolean destinationStale(long viewRevision, boolean hasDestinationWorld, LongPredicate dirtySince) {
        return destinationMemosStale(viewRevision, destinationRevision, hasDestinationWorld,
            () -> dirtySince.test(destinationVersion));
    }

    boolean destinationOverBudget(int budget) {
        for (Long2ObjectOpenHashMap<ProjectorSample> viewSamples : remoteSamples.values()) {
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

    void clearDestinationSamples() {
        for (Long2ObjectOpenHashMap<ProjectorSample> viewSamples : remoteSamples.values()) {
            viewSamples.clear();
        }
        for (Long2ByteOpenHashMap viewOcclusion : occlusion.values()) {
            viewOcclusion.clear();
        }
    }

    void refreshDestination(long viewRevision) {
        destinationRevision = viewRevision;
        if (Wormholes.projectionChangeTracker != null) {
            destinationVersion = Wormholes.projectionChangeTracker.currentVersion();
        }
    }

    void refreshLocal(boolean forceStableCellResample, boolean localDirty, long viewRevision, int budget) {
        if (localSampleMemoStale(forceStableCellResample, localDirty, viewRevision, localRevision, localAir.size(), budget)) {
            localAir.clear();
            localRevision = viewRevision;
            hasLocalRegionRect = false;
        }
    }

    void markLocalScanned() {
        if (Wormholes.projectionChangeTracker != null) {
            localChangeVersion = Wormholes.projectionChangeTracker.currentVersion();
        }
    }

    boolean localRegionDirty(UUID localWorldId) {
        ProjectionWorldChangeTracker tracker = Wormholes.projectionChangeTracker;
        if (tracker == null || localWorldId == null || !hasLocalRegionRect) {
            return true;
        }
        return tracker.dirtySince(localWorldId, localRegionChunkMinX, localRegionChunkMinZ,
            localRegionChunkMaxX, localRegionChunkMaxZ, localChangeVersion);
    }

    void expandLocalRegionRect(AxisAlignedBB region) {
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

    void discard() {
        destinationVersion = -1L;
        destinationRevision = Long.MIN_VALUE;
        localRevision = Long.MIN_VALUE;
        remoteSamples.clear();
        occlusion.clear();
        localAir.clear();
        hasLocalRegionRect = false;
        lastSampleView = null;
        lastSampleMap = null;
        lastOcclusionView = null;
        lastOcclusionMap = null;
    }

    static boolean isAir(Material material) {
        return ProjectionWorldView.isAir(material);
    }

    static boolean occludingSample(ProjectionWorldView view, int x, int y, int z) {
        Material material = view.sampleMaterial(x, y, z);
        return material != null && !ProjectionWorldView.isAir(material) && material.isOccluding();
    }

    static boolean buriedCell(boolean selfOccluding, boolean[] neighborsOccluding) {
        if (!selfOccluding) {
            return false;
        }
        for (boolean neighborOccluding : neighborsOccluding) {
            if (!neighborOccluding) {
                return false;
            }
        }
        return true;
    }

    static boolean buriedInView(ProjectionWorldView view, int x, int y, int z, BlockData selfData, boolean[] scratchNeighbors) {
        if (selfData == null) {
            return false;
        }
        Material selfMaterial = selfData.getMaterial();
        if (ProjectionWorldView.isAir(selfMaterial) || !selfMaterial.isOccluding()) {
            return false;
        }
        scratchNeighbors[0] = occludingSample(view, x + 1, y, z);
        scratchNeighbors[1] = occludingSample(view, x - 1, y, z);
        scratchNeighbors[2] = occludingSample(view, x, y + 1, z);
        scratchNeighbors[3] = occludingSample(view, x, y - 1, z);
        scratchNeighbors[4] = occludingSample(view, x, y, z + 1);
        scratchNeighbors[5] = occludingSample(view, x, y, z - 1);
        return buriedCell(true, scratchNeighbors);
    }

    static boolean localSampleMemoStale(boolean forceStableCellResample,
                                        boolean localDirty,
                                        long viewRevision,
                                        long memoRevision,
                                        int memoSize,
                                        int memoBudget) {
        return forceStableCellResample || localDirty || viewRevision != memoRevision || memoSize > memoBudget;
    }

    static boolean destinationMemosStale(long viewRevision,
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
