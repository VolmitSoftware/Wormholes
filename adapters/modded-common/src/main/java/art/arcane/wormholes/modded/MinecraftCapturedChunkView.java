package art.arcane.wormholes.modded;

import art.arcane.optics.math.CellKeys;
import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.plate.PlateCaptureJob;
import art.arcane.optics.plate.ViewPlateBuilder;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.view.ContentView;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.Objects;
import java.util.UUID;
import java.util.function.ToIntFunction;

final class MinecraftCapturedChunkView implements ContentView<BlockState, BlockState> {
    private final UUID worldId;
    private final int minHeight;
    private final int maxHeight;
    private final long revision;
    private final int minChunkX;
    private final int minChunkZ;
    private final int width;
    private final int depth;
    private final MinecraftPlateCaptureSource.CapturedChunk[] chunks;
    private final BlockState air;
    private final ToIntFunction<String> biomeIds;

    MinecraftCapturedChunkView(UUID worldId, int minHeight, int maxHeight, long revision,
                               PlateCaptureJob.Captured<MinecraftPlateCaptureSource.CapturedChunk> captured, ToIntFunction<String> biomeIds) {
        ViewPlateBuilder.Footprint footprint = captured.footprint();
        this.worldId = Objects.requireNonNull(worldId);
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
        this.revision = revision;
        this.minChunkX = footprint.minChunkX();
        this.minChunkZ = footprint.minChunkZ();
        this.width = Math.max(0, (footprint.maxChunkX() - footprint.minChunkX()) + 1);
        this.depth = Math.max(0, (footprint.maxChunkZ() - footprint.minChunkZ()) + 1);
        this.chunks = new MinecraftPlateCaptureSource.CapturedChunk[width * depth];
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < depth; dz++) {
                chunks[(dx * depth) + dz] = captured.chunk(minChunkX + dx, minChunkZ + dz);
            }
        }
        this.air = Blocks.AIR.defaultBlockState();
        this.biomeIds = Objects.requireNonNull(biomeIds);
    }

    @Override
    public boolean isEmpty(BlockBox box) {
        for (int x = box.minX() >> 4; x <= (box.minX() + box.sizeX() - 1) >> 4; x++) {
            for (int z = box.minZ() >> 4; z <= (box.minZ() + box.sizeZ() - 1) >> 4; z++) {
                MinecraftPlateCaptureSource.CapturedChunk captured = chunk(x << 4, z << 4);
                if (captured == null) {
                    return false;
                }
                int min = Math.max(box.minY(), minHeight) >> 4;
                int max = Math.min(box.minY() + box.sizeY() - 1, maxHeight - 1) >> 4;
                for (int y = min; y <= max; y++) {
                    int index = y - captured.minSectionY();
                    if (index < 0 || index >= captured.sections().length || captured.sections()[index] != null) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    @Override
    public UUID worldId() {
        return worldId;
    }

    @Override
    public int getMinHeight() {
        return minHeight;
    }

    @Override
    public int getMaxHeight() {
        return maxHeight;
    }

    @Override
    public BlockState sampleBlockData(int x, int y, int z) {
        if (y < minHeight || y >= maxHeight) {
            return null;
        }
        MinecraftPlateCaptureSource.CapturedChunk chunk = chunk(x, z);
        if (chunk == null) {
            return null;
        }
        int index = (y >> 4) - chunk.minSectionY();
        PalettedContainer<BlockState>[] sections = chunk.sections();
        if (index < 0 || index >= sections.length) {
            return null;
        }
        PalettedContainer<BlockState> section = sections[index];
        return section == null ? air : section.get(x & 15, y & 15, z & 15);
    }

    @Override
    public BlockState material(int x, int y, int z) {
        return sampleBlockData(x, y, z);
    }

    @Override
    public BlockEntitySample sampleBlockEntity(int x, int y, int z) {
        MinecraftPlateCaptureSource.CapturedChunk chunk = chunk(x, z);
        if (chunk == null || chunk.blockEntities().isEmpty()) {
            return null;
        }
        return chunk.blockEntities().get(Long.valueOf(CellKeys.pack(x, y, z)));
    }

    @Override
    public boolean blockEntitiesComplete(int x, int z) {
        MinecraftPlateCaptureSource.CapturedChunk chunk = chunk(x, z);
        return chunk == null || chunk.blockEntitiesComplete();
    }

    @Override
    public String sampleBiome(int x, int y, int z) {
        MinecraftPlateCaptureSource.CapturedChunk chunk = chunk(x, z);
        if (chunk == null || chunk.biomes().length == 0) {
            return null;
        }
        int section = Math.clamp((y >> 4) - chunk.minBiomeSection(), 0, chunk.biomes().length - 1);
        int sampledY = Math.clamp(y, minHeight, maxHeight - 1);
        return chunk.biomes()[section][((sampledY & 15) >> 2) << 4 | ((z & 15) >> 2) << 2 | (x & 15) >> 2];
    }

    @Override
    public int biomeId(int x, int y, int z) {
        return biomeIds.applyAsInt(sampleBiome(x, y, z));
    }

    @Override
    public int getLight(int x, int y, int z) {
        MinecraftPlateCaptureSource.CapturedChunk chunk = chunk(x, z);
        return chunk == null || chunk.light() == null ? LIGHT_UNAVAILABLE : chunk.light().light(x, y, z);
    }

    @Override
    public int getSkyDarken() {
        return 0;
    }

    @Override
    public boolean isChunkReady(int x, int z) {
        return chunk(x, z) != null;
    }

    @Override
    public void requestChunk(int x, int z) {
    }

    @Override
    public long getRevision() {
        return revision;
    }

    private MinecraftPlateCaptureSource.CapturedChunk chunk(int x, int z) {
        int dx = (x >> 4) - minChunkX;
        int dz = (z >> 4) - minChunkZ;
        if (dx < 0 || dz < 0 || dx >= width || dz >= depth) {
            return null;
        }
        return chunks[(dx * depth) + dz];
    }
}
