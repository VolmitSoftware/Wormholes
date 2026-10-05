package art.arcane.wormholes.modded.client.render;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;

public interface PortalSodiumSectionAccess {
    RenderSection wormholes$terrainSection(int x, int y, int z);

    boolean wormholes$visibilityReady();
}
