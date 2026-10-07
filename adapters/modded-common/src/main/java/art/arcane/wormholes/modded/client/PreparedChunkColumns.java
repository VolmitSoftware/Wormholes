package art.arcane.wormholes.modded.client;

import net.minecraft.world.level.chunk.LevelChunk;

import java.util.concurrent.atomic.AtomicReferenceArray;

public interface PreparedChunkColumns {
    AtomicReferenceArray<LevelChunk> wormholes$columns();

    int wormholes$radius();

    void wormholes$storage(PreparedChunkStorage storage, AtomicReferenceArray<LevelChunk> columns, int radius);

    void wormholes$announceColumns();
}
