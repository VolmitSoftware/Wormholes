package art.arcane.wormholes.modded.client.render.sodium;

import art.arcane.wormholes.modded.client.render.stencil.PortalLayer;
import art.arcane.wormholes.modded.client.render.stencil.TerrainBackend;

public final class SodiumTerrainBackend implements TerrainBackend {
    public static final SodiumTerrainBackend INSTANCE = new SodiumTerrainBackend();

    private SodiumTerrainBackend() {
    }

    @Override
    public String name() {
        return "sodium";
    }

    @Override
    public boolean clipsTerrain() {
        return SodiumClipShaders.ready();
    }

    @Override
    public void beginLayer(PortalLayer layer) {
        SodiumLayers.begin(layer);
    }

    @Override
    public void endLayer(PortalLayer layer) {
        SodiumLayers.end(layer);
    }
}
