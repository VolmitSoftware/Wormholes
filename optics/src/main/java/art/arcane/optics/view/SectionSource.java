package art.arcane.optics.view;

public interface SectionSource<B, M> {
    boolean columnAvailable(int chunkX, int chunkZ);

    boolean capture(int sectionX, int sectionY, int sectionZ, CachedSection.Builder<B, M> builder);

    void discardColumn(int chunkX, int chunkZ);

    void endTick();
}
