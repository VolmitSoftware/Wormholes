package art.arcane.optics.plate;

public interface ChunkCapture<W, S> {
    boolean loaded(W world, int chunkX, int chunkZ);

    ChunkLease hold(W world, int chunkX, int chunkZ);

    S capture(W world, int chunkX, int chunkZ);

    default S cached(W world, int chunkX, int chunkZ) {
        return null;
    }
}
