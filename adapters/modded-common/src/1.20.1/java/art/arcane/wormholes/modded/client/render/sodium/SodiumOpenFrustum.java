package art.arcane.wormholes.modded.client.render.sodium;

import me.jellysquid.mods.sodium.client.render.viewport.frustum.Frustum;

final class SodiumOpenFrustum implements Frustum {
    static final SodiumOpenFrustum INSTANCE = new SodiumOpenFrustum();

    private SodiumOpenFrustum() {
    }

    @Override
    public boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return true;
    }
}
