package art.arcane.wormholes.chunk.presend;

public interface ChunkPreSendPlatform<W, P> {
    boolean supported();

    boolean online(P player);

    W world(P player);

    int chunkX(P player);

    int chunkZ(P player);

    int clientViewDistance(P player);

    boolean chunkLoaded(W world, int chunkX, int chunkZ);

    boolean regionOwned(W world, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ);

    boolean alreadySent(P player, int chunkX, int chunkZ);

    boolean announceViewCenter(P player, int chunkX, int chunkZ);

    boolean sendChunk(P player, W world, int chunkX, int chunkZ);

    boolean scheduleForRegion(W world, int chunkX, int chunkZ, Runnable command, long delayTicks);

    long nanoTime();
}
