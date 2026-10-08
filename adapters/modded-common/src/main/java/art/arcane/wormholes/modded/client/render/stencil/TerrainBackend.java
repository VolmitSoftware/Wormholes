package art.arcane.wormholes.modded.client.render.stencil;

public interface TerrainBackend {
    String name();

    boolean clipsTerrain();

    void beginLayer(PortalLayer layer);

    void endLayer(PortalLayer layer);
}
