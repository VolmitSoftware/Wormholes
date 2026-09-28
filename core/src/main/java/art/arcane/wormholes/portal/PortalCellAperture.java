package art.arcane.wormholes.portal;

public interface PortalCellAperture extends PortalAperture {
    long getRevision();

    boolean isFullCuboid();

    boolean containsBlock(int x, int y, int z);
}
