package art.arcane.wormholes.modded.client.render.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;

public interface SodiumArrivalSection {
    boolean wormholes$terrainComplete();

    boolean wormholes$buildsPending();

    void wormholes$stealBuilds(ChunkBuilder builder);
}
