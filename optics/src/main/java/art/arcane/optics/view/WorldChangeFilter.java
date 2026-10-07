package art.arcane.optics.view;

public interface WorldChangeFilter {
    boolean affectsBlock(int x, int y, int z);

    boolean affectsColumn(int chunkX, int chunkZ);
}
