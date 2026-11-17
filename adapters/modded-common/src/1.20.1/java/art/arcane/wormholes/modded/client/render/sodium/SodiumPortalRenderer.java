package art.arcane.wormholes.modded.client.render.sodium;

import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;

public interface SodiumPortalRenderer {
    RenderSectionManager wormholes$sections();

    int wormholes$renderDistance();

    void wormholes$processChunkEvents();

    boolean wormholes$claimBuild(int epoch);

    boolean wormholes$discoveryArmed();

    void wormholes$discoveryArmed(boolean armed);
}
