package art.arcane.wormholes.modded.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;

public final class PortalDrawScope implements AutoCloseable {
    private static final ThreadLocal<PortalDrawScope> CURRENT = new ThreadLocal<>();

    private final PortalDrawScope previous;
    private final GpuTextureView previousColor;
    private final GpuTextureView previousDepth;
    private final RenderTarget target;
    private final RenderPass.RenderArea area;

    PortalDrawScope(RenderTarget target, PortalViewport viewport) {
        previous = CURRENT.get();
        previousColor = RenderSystem.outputColorTextureOverride;
        previousDepth = RenderSystem.outputDepthTextureOverride;
        this.target = target;
        area = new RenderPass.RenderArea(viewport.x(), viewport.y(), viewport.width(), viewport.height());
        RenderSystem.outputColorTextureOverride = target.getColorTextureView();
        RenderSystem.outputDepthTextureOverride = target.getDepthTextureView();
        CURRENT.set(this);
    }

    public static PortalDrawScope current() {
        return CURRENT.get();
    }

    public GpuTextureView color() {
        return target.getColorTextureView();
    }

    public GpuTextureView depth() {
        return target.getDepthTextureView();
    }

    public RenderPass.RenderArea area() {
        return area;
    }

    @Override
    public void close() {
        RenderSystem.outputColorTextureOverride = previousColor;
        RenderSystem.outputDepthTextureOverride = previousDepth;
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
