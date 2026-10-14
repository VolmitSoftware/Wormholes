package art.arcane.wormholes.modded.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;

public interface SkyRenderTarget {
    RenderTarget wormholes$renderTarget();

    void wormholes$renderTarget(RenderTarget target);
}
