package art.arcane.wormholes.modded;

import art.arcane.wormholes.chunk.ChunkLease;
import art.arcane.wormholes.render.view.ProjectionContentView;
import art.arcane.wormholes.render.blockentity.BlockEntityMaterials;
import art.arcane.wormholes.render.blockentity.BlockEntitySample;
import art.arcane.wormholes.render.blockentity.BlockEntitySanitizer;
import art.arcane.wormholes.render.FidelitySettings;
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

    private final WormholesModRuntime runtime;
    private final ServerLevel level;
    private final UUID worldId;
    private final int minimumHeight;
    private final int maximumHeight;
    private final Map<Long, ChunkLease> leases = new HashMap<>();
    private final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
    private long revision;
    private boolean closed;

    public MinecraftProjectionWorldView(WormholesModRuntime runtime, ServerLevel level) {
        this.runtime = Objects.requireNonNull(runtime);
        this.level = Objects.requireNonNull(level);
        runtime.requireServerThread();
        minimumHeight = level.getMinY();
        maximumHeight = level.getMaxY();
        worldId = UUID.nameUUIDFromBytes(level.dimension().identifier().toString().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public UUID worldId() {
        return worldId;
    }

    public ServerLevel getWorld() {
        return level;
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
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) {
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
        if (closed || !isChunkReady(x, z)) {
            return null;
        }
        return level.getBiome(position.set(x, y, z)).unwrapKey()
            .map(key -> key.identifier().toString()).orElse(null);
    }

    public int getLight(int x, int y, int z) {
        runtime.requireServerThread();
        if (closed || !isChunkReady(x, z)) {
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
        return !closed && level.getChunkSource().getChunkNow(x >> 4, z >> 4) != null;
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
                revision++;
            }
        }, runtime.server());
        long ticks = Math.max(1L, Math.ceilDiv(runtime.configuration().settings().getMain().arrivalWarmHoldMillis, 50L));
        if (!runtime.schedule(() -> release(key, lease), ticks)) {
            release(key, lease);
        }
    }

    @Override
    public long getRevision() {
        runtime.requireServerThread();
        return revision;
    }

    public void invalidate() {
        runtime.requireServerThread();
        revision++;
    }

    @Override
    public void close() {
        runtime.requireServerThread();
        closed = true;
        List<ChunkLease> held = new ArrayList<>(leases.values());
        leases.clear();
        for (ChunkLease lease : held) {
            lease.close();
        }
    }

    private void release(long key, ChunkLease lease) {
        runtime.requireServerThread();
        if (leases.remove(key, lease)) {
            lease.close();
        }
    }
}
