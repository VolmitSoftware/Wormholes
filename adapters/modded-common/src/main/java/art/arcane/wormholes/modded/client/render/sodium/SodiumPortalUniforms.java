package art.arcane.wormholes.modded.client.render.sodium;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;

public interface SodiumPortalUniforms {
    GpuBufferSlice wormholes$data();

    void wormholes$data(GpuBufferSlice data);

    boolean wormholes$written();

    void wormholes$written(boolean written);
}
