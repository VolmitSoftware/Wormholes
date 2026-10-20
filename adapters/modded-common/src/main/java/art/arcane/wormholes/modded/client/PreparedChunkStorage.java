package art.arcane.wormholes.modded.client;

import net.minecraft.world.level.chunk.LevelChunk;

public interface PreparedChunkStorage {
    void wormholes$announce();

    boolean wormholes$retain(LevelChunk chunk);
}
