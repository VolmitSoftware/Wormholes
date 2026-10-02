package art.arcane.wormholes.modded.client.render;

import com.mojang.renderpearl.api.textures.GpuTextureView;

public final class PortalLightmapScope implements AutoCloseable {
    private static final ThreadLocal<GpuTextureView> CURRENT = new ThreadLocal<>();
    private final GpuTextureView previous;

    PortalLightmapScope(GpuTextureView lightmap) {
        previous = CURRENT.get();
        CURRENT.set(lightmap);
    }

    public static GpuTextureView current() {
        return CURRENT.get();
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
