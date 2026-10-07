package art.arcane.wormholes.modded.client.render;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;

public interface PortalSodiumTerrainAccess {
    RenderSectionManager wormholes$terrainManager();

    void wormholes$prepareFrame();
}
