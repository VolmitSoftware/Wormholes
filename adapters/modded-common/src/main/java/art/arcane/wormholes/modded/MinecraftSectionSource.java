package art.arcane.wormholes.modded;

import art.arcane.optics.math.CellKeys;

import art.arcane.optics.view.CachedSection;
import art.arcane.optics.view.SectionSource;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.Objects;

final class MinecraftSectionSource implements SectionSource<BlockState, BlockState> {
    private final ServerLevel level;
    private final BlockState air;
    private final Long2ObjectOpenHashMap<LevelChunk> chunks = new Long2ObjectOpenHashMap<>(16);

    MinecraftSectionSource(ServerLevel level, BlockState air) {
        this.level = Objects.requireNonNull(level);
        this.air = Objects.requireNonNull(air);
    }

    @Override
    public boolean columnAvailable(int chunkX, int chunkZ) {
        return chunk(chunkX, chunkZ) != null;
    }

    @Override
    public boolean capture(int sectionX, int sectionY, int sectionZ, CachedSection.Builder<BlockState, BlockState> builder) {
        LevelChunk chunk = chunk(sectionX, sectionZ);
        if (chunk == null) {
            return false;
        }
        int index = chunk.getSectionIndexFromSectionY(sectionY);
        if (index < 0 || index >= chunk.getSectionsCount()) {
            return false;
        }
        LevelChunkSection section = chunk.getSection(index);
        if (section.hasOnlyAir()) {
            for (int cell = 0; cell < CachedSection.CELLS; cell++) {
                builder.set(cell, air, air);
            }
            return true;
        }
        for (int localY = 0; localY < 16; localY++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    BlockState state = section.getBlockState(localX, localY, localZ);
                    builder.set(CachedSection.index(localX, localY, localZ), state, state);
                }
            }
        }
        return true;
    }

    @Override
    public void discardColumn(int chunkX, int chunkZ) {
        chunks.remove(CellKeys.chunkKey(chunkX, chunkZ));
    }

    @Override
    public void endTick() {
        chunks.clear();
    }

    private LevelChunk chunk(int chunkX, int chunkZ) {
        long key = CellKeys.chunkKey(chunkX, chunkZ);
        LevelChunk cached = chunks.get(key);
        if (cached != null) {
            return cached;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk != null) {
            chunks.put(key, chunk);
        }
        return chunk;
    }
}
