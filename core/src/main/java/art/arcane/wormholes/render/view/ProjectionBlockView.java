package art.arcane.wormholes.render.view;

public interface ProjectionBlockView<B> {
    int getMinHeight();
    int getMaxHeight();
    B sampleBlockData(int x, int y, int z);
    boolean isChunkReady(int x, int z);
    void requestChunk(int x, int z);
    long getRevision();
}
