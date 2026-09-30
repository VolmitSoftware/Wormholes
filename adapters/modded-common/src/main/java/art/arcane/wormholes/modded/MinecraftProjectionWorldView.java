package art.arcane.wormholes.modded;

import art.arcane.wormholes.chunk.ChunkLease;
import art.arcane.wormholes.render.ProjectionCellKey;
import art.arcane.wormholes.render.view.CachedSection;
import art.arcane.wormholes.render.view.ProjectionContentView;
import art.arcane.wormholes.render.view.SectionCache;
import art.arcane.wormholes.render.blockentity.BlockEntityMaterials;
import art.arcane.wormholes.render.blockentity.BlockEntitySample;
import art.arcane.wormholes.render.blockentity.BlockEntitySanitizer;
import art.arcane.wormholes.render.FidelitySettings;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class MinecraftProjectionWorldView implements ProjectionContentView<BlockState, BlockState>, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final int MAX_WANTED_SECTIONS = 4096;

    private final WormholesModRuntime runtime;
    private final ServerLevel level;
    private final UUID worldId;
    private final int minimumHeight;
    private final int maximumHeight;
    private final SectionCache<BlockState, BlockState>.WorldSections sections;
    private final LongOpenHashSet wantedSections = new LongOpenHashSet(16);
    private final Map<Long, ChunkLease> leases = new HashMap<>();
    private final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
    private boolean closed;

    public MinecraftProjectionWorldView(WormholesModRuntime runtime, ServerLevel level, SectionCache<BlockState, BlockState>.WorldSections sections) {
        this.runtime = Objects.requireNonNull(runtime);
        this.level = Objects.requireNonNull(level);
        this.sections = Objects.requireNonNull(sections);
        runtime.requireServerThread();
        minimumHeight = level.getMinY();
        maximumHeight = level.getMaxY();
        worldId = worldId(level);
    }

    public static MinecraftProjectionWorldView uncached(WormholesModRuntime runtime, ServerLevel level) {
        SectionCache<BlockState, BlockState> cache = new SectionCache<>(MinecraftProjectorBlocks.INSTANCE, new SectionCache.Limits(false, 1L, 0, 1));
        return new MinecraftProjectionWorldView(runtime, level, cache.world(new MinecraftSectionSource(level, Blocks.AIR.defaultBlockState()),
            level.getMinSectionY(), level.getMaxSectionY()));
    }

    public static UUID worldId(ServerLevel level) {
        return UUID.nameUUIDFromBytes(level.dimension().identifier().toString().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public UUID worldId() {
        return worldId;
    }

    public ServerLevel getWorld() {
        return level;
    }

    public SectionCache<BlockState, BlockState>.WorldSections sections() {
        return sections;
    }

    @Override
    public int getMinHeight() {
        return minimumHeight;
    }

    @Override
    public int getMaxHeight() {
        return maximumHeight;
    }

    @Override
    public BlockState sampleBlockData(int x, int y, int z) {
        runtime.requireServerThread();
        if (closed || y < getMinHeight() || y >= getMaxHeight()) {
            return null;
        }
        CachedSection<BlockState, BlockState> cached = sections.section(x >> 4, y >> 4, z >> 4);
        if (cached != null) {
            return cached.data(CachedSection.index(x & 15, y & 15, z & 15));
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) {
            want(x, y, z);
            requestChunk(x, z);
            return null;
        }
        return chunk.getBlockState(position.set(x, y, z));
    }

    @Override
    public BlockState sampleMaterial(int x, int y, int z) {
        return sampleBlockData(x, y, z);
    }

    @Override
    public int buriedDepth(int x, int y, int z) {
        runtime.requireServerThread();
        if (closed || y < getMinHeight() || y >= getMaxHeight()) {
            return -1;
        }
        return sections.buriedDepth(x, y, z);
    }

    @Override
    public BlockEntitySample sampleBlockEntity(int x, int y, int z) {
        runtime.requireServerThread();
        if (closed || y < getMinHeight() || y >= getMaxHeight()) {
            return null;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) {
            return null;
        }
        BlockEntity entity = chunk.getBlockEntity(position.set(x, y, z));
        if (entity == null) {
            return null;
        }
        String type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString();
        if (!BlockEntityMaterials.allowed(type, FidelitySettings.blockEntityTypes, FidelitySettings.blockEntityContainers)) {
            return null;
        }
        return BlockEntitySanitizer.sanitize(type, entity.saveWithFullMetadata(level.registryAccess()),
            new BlockEntitySanitizer.Options<>(FidelitySettings.blockEntityTypes, FidelitySettings.blockEntityContainers, MinecraftBlockEntityTags.INSTANCE));
    }

    @Override
    public String sampleBiome(int x, int y, int z) {
        runtime.requireServerThread();
        if (closed || !loaded(x, z)) {
            return null;
        }
        return level.getBiome(position.set(x, y, z)).unwrapKey()
            .map(key -> key.identifier().toString()).orElse(null);
    }

    public int getLight(int x, int y, int z) {
        runtime.requireServerThread();
        if (closed || !loaded(x, z)) {
            return -1;
        }
        position.set(x, y, z);
        return ((level.getBrightness(LightLayer.SKY, position) & 15) << 4)
            | (level.getBrightness(LightLayer.BLOCK, position) & 15);
    }

    public int getSkyDarken() {
        runtime.requireServerThread();
        return level.getSkyDarken();
    }

    @Override
    public boolean isChunkReady(int x, int z) {
        runtime.requireServerThread();
        return !closed && (sections.hasColumn(x >> 4, z >> 4) || loaded(x, z));
    }

    @Override
    public void requestChunk(int x, int z) {
        runtime.requireServerThread();
        if (closed) {
            return;
        }
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        if (leases.containsKey(key)) {
            return;
        }
        ChunkLease lease = runtime.leases().retain(level, worldId, chunkX, chunkZ);
        leases.put(key, lease);
        lease.ready().whenCompleteAsync((ready, failure) -> {
            if (failure != null) {
                LOGGER.error("Could not load Wormholes projection chunk {}, {} in {}", chunkX, chunkZ, level.dimension().identifier(), failure);
            }
            if (!closed && leases.get(key) == lease) {
                runtime.projections().columnChanged(level, chunkX, chunkZ);
                chunkArrived(chunkX, chunkZ);
            }
        }, runtime.server());
        long ticks = Math.max(1L, Math.ceilDiv(runtime.configuration().settings().getMain().arrivalWarmHoldMillis, 50L));
        if (!runtime.schedule(() -> release(key, lease), ticks)) {
            release(key, lease);
        }
    }

    public void chunkArrived(int chunkX, int chunkZ) {
        runtime.requireServerThread();
        if (closed || wantedSections.isEmpty()) {
            return;
        }
        LongIterator iterator = wantedSections.iterator();
        while (iterator.hasNext()) {
            long wanted = iterator.nextLong();
            if (ProjectionCellKey.unpackX(wanted) == chunkX && ProjectionCellKey.unpackZ(wanted) == chunkZ) {
                sections.capture(chunkX, ProjectionCellKey.unpackY(wanted), chunkZ);
                iterator.remove();
            }
        }
    }

    @Override
    public long getRevision() {
        return 0L;
    }

    @Override
    public void close() {
        runtime.requireServerThread();
        closed = true;
        wantedSections.clear();
        List<ChunkLease> held = new ArrayList<>(leases.values());
        leases.clear();
        for (ChunkLease lease : held) {
            lease.close();
        }
    }

    private boolean loaded(int x, int z) {
        return level.getChunkSource().getChunkNow(x >> 4, z >> 4) != null;
    }

    private void want(int x, int y, int z) {
        if (wantedSections.size() >= MAX_WANTED_SECTIONS) {
            wantedSections.clear();
        }
        wantedSections.add(ProjectionCellKey.pack(x >> 4, y >> 4, z >> 4));
    }

    private void release(long key, ChunkLease lease) {
        runtime.requireServerThread();
        if (leases.remove(key, lease)) {
            lease.close();
        }
    }
}
