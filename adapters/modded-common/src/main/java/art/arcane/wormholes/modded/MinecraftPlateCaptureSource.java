package art.arcane.wormholes.modded;

import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.wormholes.render.ProjectionCellKey;
import art.arcane.wormholes.render.blockentity.BlockEntityMaterials;
import art.arcane.wormholes.render.blockentity.BlockEntitySample;
import art.arcane.wormholes.render.blockentity.BlockEntitySanitizer;
import art.arcane.wormholes.render.plate.ChunkLeaseHold;
import art.arcane.wormholes.render.plate.PlateCaptureJob;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class MinecraftPlateCaptureSource implements PlateCaptureJob.Source<ServerLevel, MinecraftPlateCaptureSource.CapturedChunk> {
    private final WormholesModRuntime runtime;
    private final UUID worldId;
    private final boolean blockEntities;

    MinecraftPlateCaptureSource(WormholesModRuntime runtime, UUID worldId, boolean blockEntities) {
        this.runtime = Objects.requireNonNull(runtime);
        this.worldId = Objects.requireNonNull(worldId);
        this.blockEntities = blockEntities;
    }

    record CapturedChunk(int minSectionY, PalettedContainer<BlockState>[] sections, Map<Long, BlockEntitySample> blockEntities,
                         boolean blockEntitiesComplete) {
    }

    @Override
    public boolean loaded(ServerLevel world, int chunkX, int chunkZ) {
        return world.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
    }

    @Override
    public PlateCaptureJob.Hold hold(ServerLevel world, int chunkX, int chunkZ) {
        return new ChunkLeaseHold(runtime.leases().retain(world, worldId, chunkX, chunkZ));
    }

    @Override
    @SuppressWarnings("unchecked")
    public CapturedChunk capture(ServerLevel world, int chunkX, int chunkZ) {
        runtime.requireServerThread();
        LevelChunk chunk = world.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            throw new IllegalStateException("Plate capture chunk " + chunkX + ", " + chunkZ + " unloaded in " + worldId);
        }
        int count = chunk.getSectionsCount();
        PalettedContainer<BlockState>[] sections = new PalettedContainer[count];
        for (int index = 0; index < count; index++) {
            LevelChunkSection section = chunk.getSection(index);
            sections[index] = section.hasOnlyAir() ? null : section.getStates().copy();
        }
        Map<Long, BlockEntitySample> samples = blockEntities ? captureBlockEntities(world, chunk) : Map.of();
        return new CapturedChunk(chunk.getMinSectionY(), sections, samples, samples.size() < PlateCaptureJob.MAX_BLOCK_ENTITIES_PER_CHUNK);
    }

    private static Map<Long, BlockEntitySample> captureBlockEntities(ServerLevel world, LevelChunk chunk) {
        Map<Long, BlockEntitySample> samples = new HashMap<>(8);
        for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
            if (samples.size() >= PlateCaptureJob.MAX_BLOCK_ENTITIES_PER_CHUNK) {
                break;
            }
            BlockEntity entity = entry.getValue();
            String type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString();
            if (!BlockEntityMaterials.allowed(type, FidelitySettings.blockEntityTypes, FidelitySettings.blockEntityContainers)) {
                continue;
            }
            BlockEntitySample sample = BlockEntitySanitizer.sanitize(type, entity.saveWithFullMetadata(world.registryAccess()),
                new BlockEntitySanitizer.Options<>(FidelitySettings.blockEntityTypes, FidelitySettings.blockEntityContainers, MinecraftBlockEntityTags.INSTANCE));
            if (sample != null) {
                BlockPos position = entry.getKey();
                samples.put(Long.valueOf(ProjectionCellKey.pack(position.getX(), position.getY(), position.getZ())), sample);
            }
        }
        return samples;
    }
}
