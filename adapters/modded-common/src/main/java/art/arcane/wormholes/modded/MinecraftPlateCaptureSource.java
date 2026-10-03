package art.arcane.wormholes.modded;

import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.wormholes.modded.clientview.MinecraftLightSnapshot;
import art.arcane.wormholes.render.plate.PlateBox;
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
    private final int minY;
    private final int maxY;
    private final boolean environment;
    private final Options options;

    MinecraftPlateCaptureSource(WormholesModRuntime runtime, Options options) {
        this.runtime = Objects.requireNonNull(runtime);
        this.worldId = Objects.requireNonNull(options.worldId());
        this.blockEntities = options.blockEntities();
        this.minY = options.minY();
        this.maxY = options.maxY();
        this.environment = options.environment();
        this.options = options;
    }

    record Options(UUID worldId, boolean blockEntities, int minY, int maxY, boolean environment) {
        static Options column(UUID worldId, boolean blockEntities) {
            return new Options(worldId, blockEntities, Integer.MIN_VALUE, Integer.MAX_VALUE, false);
        }
    }

    record CapturedChunk(int minSectionY, PalettedContainer<BlockState>[] sections, Map<Long, BlockEntitySample> blockEntities,
                         boolean blockEntitiesComplete, MinecraftLightSnapshot light, int minBiomeSection, String[][] biomes) {
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
    public CapturedChunk cached(ServerLevel world, int chunkX, int chunkZ) {
        runtime.requireServerThread();
        LevelChunk chunk = world.getChunkSource().getChunkNow(chunkX, chunkZ);
        return chunk == null ? null : runtime.projections().plateSnapshots().get(
            new MinecraftPlateSnapshotCache.Key(chunkX, chunkZ, options), chunk, world.getGameTime());
    }

    @Override
    @SuppressWarnings("unchecked")
    public CapturedChunk capture(ServerLevel world, int chunkX, int chunkZ) {
        runtime.requireServerThread();
        LevelChunk chunk = world.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            throw new IllegalStateException("Plate capture chunk " + chunkX + ", " + chunkZ + " unloaded in " + worldId);
        }
        int minSection = Math.max(chunk.getMinSectionY(), minY >> 4);
        int maxSection = Math.min(chunk.getMinSectionY() + chunk.getSectionsCount(), (maxY >> 4) + 1);
        int count = Math.max(0, maxSection - minSection);
        PalettedContainer<BlockState>[] sections = new PalettedContainer[count];
        for (int index = 0; index < count; index++) {
            LevelChunkSection section = chunk.getSection(minSection - chunk.getMinSectionY() + index);
            sections[index] = section.hasOnlyAir() ? null : section.getStates().copy();
        }
        Map<Long, BlockEntitySample> samples = blockEntities ? captureBlockEntities(world, chunk) : Map.of();
        int biomeMin = environment ? Math.clamp(minY >> 4, chunk.getMinSectionY(), chunk.getMinSectionY() + chunk.getSectionsCount() - 1) : 0;
        int biomeMax = environment ? Math.clamp(maxY >> 4, chunk.getMinSectionY(), chunk.getMinSectionY() + chunk.getSectionsCount() - 1) : 0;
        String[][] biomes = environment ? new String[biomeMax - biomeMin + 1][64] : new String[0][];
        for (int section = 0; section < biomes.length; section++) {
            LevelChunkSection source = chunk.getSection(biomeMin + section - chunk.getMinSectionY());
            for (int cell = 0; cell < 64; cell++) {
                biomes[section][cell] = source.getNoiseBiome(cell & 3, cell >> 4, cell >> 2 & 3)
                    .unwrapKey().orElseThrow().identifier().toString();
            }
        }
        MinecraftLightSnapshot light = environment ? MinecraftLightSnapshot.capture(world,
            new PlateBox(chunkX << 4, minY, chunkZ << 4, 16, maxY - minY + 1, 16)) : null;
        CapturedChunk captured = new CapturedChunk(minSection, sections, samples, samples.size() < PlateCaptureJob.MAX_BLOCK_ENTITIES_PER_CHUNK,
            light, biomeMin, biomes);
        runtime.projections().plateSnapshots().put(new MinecraftPlateSnapshotCache.Key(chunkX, chunkZ, options), chunk, world.getGameTime(), captured);
        return captured;
    }

    private Map<Long, BlockEntitySample> captureBlockEntities(ServerLevel world, LevelChunk chunk) {
        Map<Long, BlockEntitySample> samples = new HashMap<>(8);
        for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
            if (entry.getKey().getY() < minY || entry.getKey().getY() > maxY) {
                continue;
            }
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
