package art.arcane.wormholes.modded.client.render;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.DynamicUniforms;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.util.Optional;
import java.util.OptionalDouble;

public final class PortalPasses {
    private PortalPasses() {
    }

    public static RenderPass open(String label, GpuTextureView color, GpuTextureView depth) {
        return RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> label, color, Optional.empty(), depth, OptionalDouble.empty());
    }

    public static RenderPass cleared(String label, GpuTextureView color, GpuTextureView depth) {
        return RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> label, color, Optional.of(new Vector4f(0.0f)), depth,
            OptionalDouble.of(0.0));
    }

    public static RenderPass area(String label, RenderTarget target, Optional<Vector4fc> clear, PortalViewport viewport) {
        return RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> label, target.getColorTextureView(), clear,
            target.getDepthTextureView(), clear.isPresent() ? OptionalDouble.of(0.0) : OptionalDouble.empty(),
            new RenderPass.RenderArea(viewport.x(), viewport.y(), viewport.width(), viewport.height()));
    }

    public static GpuBufferSlice transform(Matrix4f modelView) {
        return RenderSystem.getDynamicUniforms().writeTransform(modelView);
    }

    public static GpuBufferSlice transform(Matrix4f modelView, Vector4f color) {
        return RenderSystem.getDynamicUniforms().writeTransform(modelView, color);
    }

    public static GpuBufferSlice transform(DynamicUniforms.Transform transform) {
        return RenderSystem.getDynamicUniforms().writeTransform(transform);
    }

    public static BufferBuilder quads(ByteBufferBuilder allocation, VertexFormat format) {
        return new BufferBuilder(allocation, PrimitiveTopology.QUADS, format);
    }
}
