package art.arcane.wormholes.modded.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;

import java.util.OptionalDouble;
import java.util.OptionalInt;

public final class PortalDrawScope implements AutoCloseable {
    private static final ThreadLocal<PortalDrawScope> CURRENT = new ThreadLocal<>();

    private final PortalDrawScope previous;
    private final GpuTextureView previousColor;
    private final GpuTextureView previousDepth;
    private final RenderTarget target;
    private final PortalViewport viewport;

    PortalDrawScope(RenderTarget target, PortalViewport viewport) {
        previous = CURRENT.get();
        previousColor = RenderSystem.outputColorTextureOverride;
        previousDepth = RenderSystem.outputDepthTextureOverride;
        this.target = target;
        this.viewport = viewport;
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

    public boolean clear(CommandEncoder encoder, OptionalInt clear, OptionalDouble clearDepth) {
        GpuTextureView depth = depth();
        if (clear.isEmpty() || clearDepth.isEmpty() || depth == null) {
            return false;
        }
        encoder.clearColorAndDepthTextures(color().texture(), clear.getAsInt(), depth.texture(), clearDepth.getAsDouble(),
            viewport.x(), viewport.y(), viewport.width(), viewport.height());
        return true;
    }

    public RenderPass restrict(RenderPass pass) {
        pass.enableScissor(viewport.x(), viewport.y(), viewport.width(), viewport.height());
        return pass;
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
