package art.arcane.optics.aperture;

public interface CellAperture extends Aperture {
    long getRevision();

    boolean isFullCuboid();

    boolean containsBlock(int x, int y, int z);
}
