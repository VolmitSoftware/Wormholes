package art.arcane.optics.view;

public interface BlockView<B> {
    int getMinHeight();
    int getMaxHeight();
    B sampleBlockData(int x, int y, int z);
    boolean isChunkReady(int x, int z);
    void requestChunk(int x, int z);
    long getRevision();
}
