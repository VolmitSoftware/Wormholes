package art.arcane.wormholes.modded;

import art.arcane.wormholes.render.ProjectionCellKey;
import art.arcane.wormholes.render.blockentity.BlockEntitySample;
import art.arcane.wormholes.render.plate.PlateCaptureJob;
import art.arcane.wormholes.render.plate.ViewPlateBuilder;
import art.arcane.wormholes.render.view.ProjectionContentView;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.Objects;
import java.util.UUID;

final class MinecraftCapturedChunkView implements ProjectionContentView<BlockState, BlockState> {
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

    MinecraftCapturedChunkView(UUID worldId, int minHeight, int maxHeight, long revision,
                               PlateCaptureJob.Captured<MinecraftPlateCaptureSource.CapturedChunk> captured) {
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
    public BlockState sampleMaterial(int x, int y, int z) {
        return sampleBlockData(x, y, z);
    }

    @Override
    public BlockEntitySample sampleBlockEntity(int x, int y, int z) {
        MinecraftPlateCaptureSource.CapturedChunk chunk = chunk(x, z);
        if (chunk == null || chunk.blockEntities().isEmpty()) {
            return null;
        }
        return chunk.blockEntities().get(Long.valueOf(ProjectionCellKey.pack(x, y, z)));
    }

    @Override
    public boolean blockEntitiesComplete(int x, int z) {
        MinecraftPlateCaptureSource.CapturedChunk chunk = chunk(x, z);
        return chunk == null || chunk.blockEntitiesComplete();
    }

    @Override
    public String sampleBiome(int x, int y, int z) {
        return null;
    }

    @Override
    public int getLight(int x, int y, int z) {
        return LIGHT_UNAVAILABLE;
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
