package art.arcane.wormholes.network.replication.capture;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.replication.BlockChange;
import art.arcane.wormholes.network.replication.BlockChangeFeed;
import art.arcane.wormholes.network.replication.BlockEntityDiff;
import art.arcane.wormholes.network.replication.ChunkReplicationManager;
import art.arcane.wormholes.network.replication.LightDiff;
import art.arcane.wormholes.network.view.ViewSlice;
import art.arcane.wormholes.render.view.OccludedMarker;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class RegionalDiffAccumulator {
    static final int MAX_BLOCK_DIFFS_PER_CHANGE = 25;

    private static final int STATE_STRING_CACHE_MAX = 4096;
    private static final int OCCLUSION_CACHE_RADIUS = 4;
    private static final int OCCLUSION_CACHE_SPAN = (OCCLUSION_CACHE_RADIUS * 2) + 1;
    private static final int OCCLUSION_CACHE_SIZE = OCCLUSION_CACHE_SPAN * OCCLUSION_CACHE_SPAN * OCCLUSION_CACHE_SPAN;

    @FunctionalInterface
    public interface BlockReader {
        BlockData at(World world, int x, int y, int z);
    }

    @FunctionalInterface
    public interface DataOcclusion {
        boolean occluding(BlockData data);
    }

    @FunctionalInterface
    interface SnapshotBlockReader {
        BlockData at(int x, int y, int z);
    }

    record BlockCaptureRevision(ChunkDirtySet dirtySet, long value) {
    }

    private final ChunkReplicationManager replication;
    private final BlockChangeFeed feed;
    private volatile CaptureSettings settings;
    private volatile BlockReader blockReader = (world, x, y, z) -> world.getBlockAt(x, y, z).getBlockData();
    private volatile DataOcclusion dataOcclusion = OccludedMarker::isOccluding;
    private final Map<UUID, Map<Long, ChunkDirtySet>> worldDirty = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Long, ChunkLightShadow>> worldLightShadows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<BlockData, String> stateStrings = new ConcurrentHashMap<>(256);
    private final ThreadLocal<byte[]> occlusionCaches = ThreadLocal.withInitial(() -> new byte[OCCLUSION_CACHE_SIZE]);
    private final AtomicLong blocksCaptured = new AtomicLong();
    private final AtomicLong blocksDropped = new AtomicLong();
    private final AtomicLong overflowDrops = new AtomicLong();
    private final AtomicLong lightDiffsCaptured = new AtomicLong();
    private final AtomicLong entityDiffsCaptured = new AtomicLong();
    private final AtomicLong tickDrains = new AtomicLong();

    public RegionalDiffAccumulator(ChunkReplicationManager replication, BlockChangeFeed feed, CaptureSettings settings) {
        this.replication = replication;
        this.feed = feed;
        this.settings = settings == null ? CaptureSettings.defaults() : settings;
    }

    public void applySettings(CaptureSettings next) {
        this.settings = next == null ? CaptureSettings.defaults() : next;
    }

    public CaptureSettings settings() {
        return settings;
    }

    public boolean isRelevant(World world, long chunkKey) {
        if (world == null) {
            return false;
        }
        return replication.hasSubscribers(world, chunkKey);
    }

    BlockCaptureRevision captureBlockRevision(World world, long chunkKey) {
        ChunkDirtySet dirtySet = dirtySetFor(world, chunkKey);
        return new BlockCaptureRevision(dirtySet, dirtySet.liveBlockRevision());
    }

    boolean isBlockRevisionCurrent(World world, long chunkKey, BlockCaptureRevision revision) {
        if (world == null || revision == null) {
            return false;
        }
        Map<Long, ChunkDirtySet> worldMap = worldDirty.get(world.getUID());
        if (worldMap == null || worldMap.get(Long.valueOf(chunkKey)) != revision.dirtySet()) {
            return false;
        }
        return revision.dirtySet().liveBlockRevision() == revision.value();
    }

    public void recordBlockChange(World world, int worldX, int worldY, int worldZ, BlockData newData, byte flags) {
        if (world == null || newData == null) {
            return;
        }
        recordBlockChange(world, worldX, worldY, worldZ, newData, flags,
            (x, y, z) -> blockReader.at(world, x, y, z), world.getMinHeight(), world.getMaxHeight(), null);
    }

    void recordSnapshotBlockChange(World world,
                                   int worldX,
                                   int worldY,
                                   int worldZ,
                                   BlockData newData,
                                   byte flags,
                                   SnapshotBlockReader snapshot,
                                   int minHeight,
                                   int maxHeight,
                                   BlockCaptureRevision revision) {
        if (world == null || newData == null || snapshot == null || revision == null) {
            return;
        }
        recordBlockChange(world, worldX, worldY, worldZ, newData, flags, snapshot, minHeight, maxHeight, revision);
    }

    private void recordBlockChange(World world,
                                   int worldX,
                                   int worldY,
                                   int worldZ,
                                   BlockData newData,
                                   byte flags,
                                   SnapshotBlockReader reader,
                                   int minHeight,
                                   int maxHeight,
                                   BlockCaptureRevision snapshotRevision) {
        int chunkX = worldX >> 4;
        int chunkZ = worldZ >> 4;
        long chunkKey = ViewSlice.columnKey(chunkX, chunkZ);
        if (!replication.hasSubscribers(world, chunkKey)) {
            if (Settings.DEBUG) {
                Wormholes.v("[block] DROP " + worldX + "," + worldY + "," + worldZ + " chunk=" + chunkX + "," + chunkZ + " - no view subscriber for that chunk (not inside any viewed gateway's capture box)");
            }
            return;
        }
        ChunkDirtySet set;
        if (snapshotRevision == null) {
            set = dirtySetFor(world, chunkKey);
            set.advanceLiveBlockRevision();
        } else {
            if (!isBlockRevisionCurrent(world, chunkKey, snapshotRevision)) {
                return;
            }
            set = snapshotRevision.dirtySet();
        }
        boolean buriedCellCulling = replication.hasBuriedCellCullingSubscriber(world, chunkKey);
        byte storedFlags = flags;
        byte[] occlusionCache = null;
        boolean centerOccluding = false;
        if (buriedCellCulling) {
            occlusionCache = occlusionCaches.get();
            Arrays.fill(occlusionCache, (byte) 0);
            centerOccluding = dataOcclusion.occluding(newData);
            if (deeplyBuried(chunkX, chunkZ, worldX, worldY, worldZ, centerOccluding, worldX, worldY, worldZ,
                occlusionCache, reader, minHeight, maxHeight)) {
                storedFlags = (byte) (flags | BlockChange.FLAG_OCCLUDED);
            }
        }
        int lx = worldX & 0xF;
        int lz = worldZ & 0xF;
        int packed = BlockChange.pack(lx, worldY, lz);
        String stateString = stateStringFor(newData);
        if (Settings.DEBUG) {
            Wormholes.v("[block] CAPTURE " + stateString + " at " + worldX + "," + worldY + "," + worldZ + " chunk=" + chunkX + "," + chunkZ + " -> queued diff");
        }
        int currentCap = settings.maxQueuedDiffsPerChunk();
        if (snapshotRevision == null) {
            if (!set.putBlockIfBelowCapacity(packed, stateString, storedFlags, currentCap)) {
                forceChunkResync(world, chunkKey, set);
                return;
            }
        } else {
            ChunkDirtySet.BlockPutResult result = set.putSnapshotBlockIfBelowCapacity(
                packed, stateString, storedFlags, currentCap, snapshotRevision.value());
            if (result == ChunkDirtySet.BlockPutResult.STALE_REVISION) {
                return;
            }
            if (result == ChunkDirtySet.BlockPutResult.CAPACITY_EXCEEDED) {
                forceChunkResync(world, chunkKey, set);
                return;
            }
        }
        blocksCaptured.incrementAndGet();
        if (buriedCellCulling) {
            boolean reemitted = reemitAffectedCells(
                chunkX, chunkZ, worldX, worldY, worldZ, centerOccluding, set, currentCap,
                occlusionCache, reader, minHeight, maxHeight, snapshotRevision);
            if (!reemitted) {
                forceChunkResync(world, chunkKey, set);
            }
        }
    }

    void setCaptureOcclusionModel(BlockReader reader, DataOcclusion occlusion) {
        this.blockReader = reader;
        this.dataOcclusion = occlusion;
    }

    private boolean reemitAffectedCells(int chunkX,
                                        int chunkZ,
                                        int cx,
                                        int cy,
                                        int cz,
                                        boolean centerOccluding,
                                        ChunkDirtySet set,
                                        int capacity,
                                        byte[] cache,
                                        SnapshotBlockReader reader,
                                        int minHeight,
                                        int maxHeight,
                                        BlockCaptureRevision snapshotRevision) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int distance = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (distance == 0 || distance > 2) {
                        continue;
                    }
                    if (!reemitAffectedCell(chunkX, chunkZ, cx, cy, cz, centerOccluding,
                        cx + dx, cy + dy, cz + dz, set, capacity, cache, reader, minHeight, maxHeight,
                        snapshotRevision)) {
                        return false;
                    }
                    if (snapshotRevision != null
                        && set.liveBlockRevision() != snapshotRevision.value()) {
                        return true;
                    }
                }
            }
        }
        return true;
    }

    private boolean reemitAffectedCell(int chunkX,
                                       int chunkZ,
                                       int cx,
                                       int cy,
                                       int cz,
                                       boolean centerOccluding,
                                       int tx,
                                       int ty,
                                       int tz,
                                       ChunkDirtySet set,
                                       int capacity,
                                       byte[] cache,
                                       SnapshotBlockReader reader,
                                       int minHeight,
                                       int maxHeight,
                                       BlockCaptureRevision snapshotRevision) {
        if ((tx >> 4) != chunkX || (tz >> 4) != chunkZ) {
            return true;
        }
        if (ty < minHeight || ty >= maxHeight) {
            return true;
        }
        BlockData data = reader.at(tx, ty, tz);
        if (!dataOcclusion.occluding(data)) {
            return true;
        }
        byte flags = deeplyBuried(
            chunkX, chunkZ, cx, cy, cz, centerOccluding, tx, ty, tz, cache, reader, minHeight, maxHeight)
            ? BlockChange.FLAG_OCCLUDED : BlockChange.FLAG_NONE;
        int packed = BlockChange.pack(tx & 0xF, ty, tz & 0xF);
        ChunkDirtySet.BlockPutResult result = snapshotRevision == null
            ? set.putBlockOcclusionIfBelowCapacity(packed, stateStringFor(data), flags, capacity)
            : set.putSnapshotBlockOcclusionIfBelowCapacity(
                packed, stateStringFor(data), flags, capacity, snapshotRevision.value());
        if (result == ChunkDirtySet.BlockPutResult.CAPACITY_EXCEEDED) {
            return false;
        }
        if (result == ChunkDirtySet.BlockPutResult.STALE_REVISION) {
            return true;
        }
        if (result == ChunkDirtySet.BlockPutResult.INSERTED) {
            blocksCaptured.incrementAndGet();
        }
        return true;
    }

    private boolean deeplyBuried(int chunkX,
                                 int chunkZ,
                                 int cx,
                                 int cy,
                                 int cz,
                                 boolean centerOccluding,
                                 int tx,
                                 int ty,
                                 int tz,
                                 byte[] cache,
                                 SnapshotBlockReader reader,
                                 int minHeight,
                                 int maxHeight) {
        if (!surrounded(chunkX, chunkZ, cx, cy, cz, centerOccluding,
            tx, ty, tz, cache, reader, minHeight, maxHeight)) {
            return false;
        }
        return surrounded(chunkX, chunkZ, cx, cy, cz, centerOccluding,
            tx + 1, ty, tz, cache, reader, minHeight, maxHeight)
            && surrounded(chunkX, chunkZ, cx, cy, cz, centerOccluding,
                tx - 1, ty, tz, cache, reader, minHeight, maxHeight)
            && surrounded(chunkX, chunkZ, cx, cy, cz, centerOccluding,
                tx, ty + 1, tz, cache, reader, minHeight, maxHeight)
            && surrounded(chunkX, chunkZ, cx, cy, cz, centerOccluding,
                tx, ty - 1, tz, cache, reader, minHeight, maxHeight)
            && surrounded(chunkX, chunkZ, cx, cy, cz, centerOccluding,
                tx, ty, tz + 1, cache, reader, minHeight, maxHeight)
            && surrounded(chunkX, chunkZ, cx, cy, cz, centerOccluding,
                tx, ty, tz - 1, cache, reader, minHeight, maxHeight);
    }

    private boolean surrounded(int chunkX,
                               int chunkZ,
                               int cx,
                               int cy,
                               int cz,
                               boolean centerOccluding,
                               int tx,
                               int ty,
                               int tz,
                               byte[] cache,
                               SnapshotBlockReader reader,
                               int minHeight,
                               int maxHeight) {
        if (!occlusionAt(chunkX, chunkZ, cx, cy, cz, centerOccluding, tx, ty, tz, cache, reader, minHeight, maxHeight)) {
            return false;
        }
        return occlusionAt(chunkX, chunkZ, cx, cy, cz, centerOccluding, tx + 1, ty, tz, cache, reader, minHeight, maxHeight)
            && occlusionAt(chunkX, chunkZ, cx, cy, cz, centerOccluding, tx - 1, ty, tz, cache, reader, minHeight, maxHeight)
            && occlusionAt(chunkX, chunkZ, cx, cy, cz, centerOccluding, tx, ty + 1, tz, cache, reader, minHeight, maxHeight)
            && occlusionAt(chunkX, chunkZ, cx, cy, cz, centerOccluding, tx, ty - 1, tz, cache, reader, minHeight, maxHeight)
            && occlusionAt(chunkX, chunkZ, cx, cy, cz, centerOccluding, tx, ty, tz + 1, cache, reader, minHeight, maxHeight)
            && occlusionAt(chunkX, chunkZ, cx, cy, cz, centerOccluding, tx, ty, tz - 1, cache, reader, minHeight, maxHeight);
    }

    private boolean occlusionAt(int chunkX, int chunkZ, int cx, int cy, int cz, boolean centerOccluding,
                                int x, int y, int z, byte[] cache, SnapshotBlockReader reader,
                                int minHeight, int maxHeight) {
        if (x == cx && y == cy && z == cz) {
            return centerOccluding;
        }
        if ((x >> 4) != chunkX || (z >> 4) != chunkZ) {
            return false;
        }
        if (y < minHeight || y >= maxHeight) {
            return false;
        }
        int dx = x - cx;
        int dy = y - cy;
        int dz = z - cz;
        int cacheIndex = occlusionCacheIndex(dx, dy, dz);
        if (cacheIndex >= 0 && cache[cacheIndex] != 0) {
            return cache[cacheIndex] == 1;
        }
        boolean occluding = dataOcclusion.occluding(reader.at(x, y, z));
        if (cacheIndex >= 0) {
            cache[cacheIndex] = occluding ? (byte) 1 : (byte) 2;
        }
        return occluding;
    }

    private static int occlusionCacheIndex(int dx, int dy, int dz) {
        if (Math.abs(dx) > OCCLUSION_CACHE_RADIUS
            || Math.abs(dy) > OCCLUSION_CACHE_RADIUS
            || Math.abs(dz) > OCCLUSION_CACHE_RADIUS) {
            return -1;
        }
        return (((dx + OCCLUSION_CACHE_RADIUS) * OCCLUSION_CACHE_SPAN
            + (dy + OCCLUSION_CACHE_RADIUS)) * OCCLUSION_CACHE_SPAN)
            + dz + OCCLUSION_CACHE_RADIUS;
    }

    private void forceChunkResync(World world, long chunkKey, ChunkDirtySet set) {
        overflowDrops.incrementAndGet();
        blocksDropped.incrementAndGet();
        set.drainAll();
        replication.forceResync(world, chunkKey);
    }

    public void recordBlockEntityChange(World world, int worldX, int worldY, int worldZ, byte[] nbt) {
        if (!settings.blockEntityCaptureEnabled() || world == null || nbt == null) {
            return;
        }
        int chunkX = worldX >> 4;
        int chunkZ = worldZ >> 4;
        long chunkKey = ViewSlice.columnKey(chunkX, chunkZ);
        if (!replication.hasSubscribers(world, chunkKey)) {
            return;
        }
        int packed = BlockChange.pack(worldX & 0xF, worldY, worldZ & 0xF);
        ChunkDirtySet set = dirtySetFor(world, chunkKey);
        set.putBlockEntity(packed, new BlockEntityDiff(packed, nbt));
        entityDiffsCaptured.incrementAndGet();
    }

    public void recordLightSection(World world, long chunkKey, LightDiff diff) {
        if (!settings.lightCaptureEnabled() || world == null || diff == null || diff.data() == null) {
            return;
        }
        if (diff.data().length != LightDiff.DATA_LENGTH) {
            return;
        }
        if (!replication.hasSubscribers(world, chunkKey)) {
            return;
        }
        ChunkDirtySet set = dirtySetFor(world, chunkKey);
        if (diff.lightType() == LightDiff.TYPE_BLOCKLIGHT) {
            set.putBlockLight(diff);
        } else {
            set.putSkyLight(diff);
        }
        lightDiffsCaptured.incrementAndGet();
    }

    public ChunkLightShadow lightShadowFor(World world, long chunkKey) {
        Map<Long, ChunkLightShadow> worldMap = worldLightShadows.computeIfAbsent(world.getUID(), ignored -> new ConcurrentHashMap<>());
        return worldMap.computeIfAbsent(chunkKey, ignored -> new ChunkLightShadow());
    }

    public ChunkLightShadow lightShadowIfPresent(World world, long chunkKey) {
        Map<Long, ChunkLightShadow> worldMap = worldLightShadows.get(world.getUID());
        if (worldMap == null) {
            return null;
        }
        return worldMap.get(chunkKey);
    }

    public ChunkDirtySet dirtySetFor(World world, long chunkKey) {
        Map<Long, ChunkDirtySet> worldMap = worldDirty.computeIfAbsent(world.getUID(), ignored -> new ConcurrentHashMap<>());
        return worldMap.computeIfAbsent(chunkKey, ChunkDirtySet::new);
    }

    public Map<UUID, Map<Long, ChunkDirtySet>> dirtyWorlds() {
        return worldDirty;
    }

    public void drainChunk(World world, long chunkKey) {
        drainChunk(world, chunkKey, null);
    }

    public void drainChunk(World world, long chunkKey, PreDrainHook hook) {
        Map<Long, ChunkDirtySet> worldMap = worldDirty.get(world.getUID());
        if (worldMap == null) {
            return;
        }
        ChunkDirtySet set = worldMap.get(chunkKey);
        if (set == null) {
            return;
        }
        if (hook != null) {
            hook.beforeDrain(world, chunkKey, set.snapshotBlockPacked());
        }
        ChunkDirtySet.Drain drained;
        synchronized (set) {
            if (set.isEmpty()) {
                return;
            }
            drained = set.drainAll();
        }
        dispatchDrain(world, chunkKey, drained);
    }

    public interface PreDrainHook {
        void beforeDrain(World world, long chunkKey, java.util.Set<Integer> blockChangePackedXyzs);
    }

    public void drainAll() {
        drainAll(null);
    }

    public void drainAll(PreDrainHook hook) {
        for (Map.Entry<UUID, Map<Long, ChunkDirtySet>> worldEntry : worldDirty.entrySet()) {
            UUID worldId = worldEntry.getKey();
            World world = Bukkit.getWorld(worldId);
            if (world == null) {
                continue;
            }
            for (Map.Entry<Long, ChunkDirtySet> chunkEntry : worldEntry.getValue().entrySet()) {
                long chunkKey = chunkEntry.getKey();
                ChunkDirtySet set = chunkEntry.getValue();
                if (hook != null) {
                    hook.beforeDrain(world, chunkKey, set.snapshotBlockPacked());
                }
                ChunkDirtySet.Drain drained;
                synchronized (set) {
                    if (set.isEmpty()) {
                        continue;
                    }
                    drained = set.drainAll();
                }
                dispatchDrain(world, chunkKey, drained);
            }
        }
        tickDrains.incrementAndGet();
    }

    public void resetChunk(UUID worldId, long chunkKey) {
        if (worldId == null) {
            return;
        }
        Map<Long, ChunkDirtySet> dirtyMap = worldDirty.get(worldId);
        if (dirtyMap != null) {
            dirtyMap.remove(chunkKey);
        }
        Map<Long, ChunkLightShadow> lightMap = worldLightShadows.get(worldId);
        if (lightMap != null) {
            lightMap.remove(chunkKey);
        }
    }

    public boolean hasPendingLight(UUID worldId, long chunkKey) {
        Map<Long, ChunkLightShadow> lightMap = worldLightShadows.get(worldId);
        if (lightMap == null) {
            return false;
        }
        ChunkLightShadow shadow = lightMap.get(chunkKey);
        return shadow != null && shadow.hasPending();
    }

    public Stats stats() {
        return new Stats(
            blocksCaptured.get(),
            blocksDropped.get(),
            lightDiffsCaptured.get(),
            entityDiffsCaptured.get(),
            overflowDrops.get(),
            tickDrains.get()
        );
    }

    public record Stats(long blocksCaptured, long blocksDropped, long lightDiffsCaptured, long entityDiffsCaptured, long overflowDrops, long tickDrains) {
    }

    private String stateStringFor(BlockData data) {
        String cached = stateStrings.get(data);
        if (cached != null) {
            return cached;
        }
        String computed = data.getAsString();
        BlockData key = data.clone();
        if (key == null) {
            return computed;
        }
        if (stateStrings.size() >= STATE_STRING_CACHE_MAX) {
            stateStrings.clear();
        }
        stateStrings.put(key, computed);
        return computed;
    }

    private void dispatchDrain(World world, long chunkKey, ChunkDirtySet.Drain drained) {
        feed.onChunkDrain(world, chunkKey, drained.blocks(), drained.lights(), drained.entities());
    }
}
