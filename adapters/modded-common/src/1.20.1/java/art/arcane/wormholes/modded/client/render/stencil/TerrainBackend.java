package art.arcane.wormholes.modded.client.render.stencil;

/** Draws terrain inside portal layers: vanilla chunk rendering, or Sodium/Embeddium when installed. */
public interface TerrainBackend {
    /** Short name for debug output. */
    String name();

    /** Whether this backend clips terrain against the layer's clip plane itself. */
    boolean clipsTerrain();

    /** Swaps visible sections and terrain state to the layer before its {@code LevelRenderer.renderLevel} re-entry. */
    void beginLayer(PortalLayer layer);

    /** Restores the state swapped by {@link #beginLayer}. */
    void endLayer(PortalLayer layer);
}
