package art.arcane.wormholes.modded.client.render;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.util.ARGB;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

public final class PortalPasses {
    private static final Vector4f WHITE = new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);
    private static final Vector3f NO_OFFSET = new Vector3f();
    private static final Matrix4f IDENTITY = new Matrix4f();
    private static final double FAR_DEPTH = 1.0;

    private PortalPasses() {
    }

    public static RenderPass open(String label, GpuTextureView color, GpuTextureView depth) {
        return RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> label, color, OptionalInt.empty(), depth, OptionalDouble.empty());
    }

    public static RenderPass cleared(String label, GpuTextureView color, GpuTextureView depth) {
        return RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> label, color, OptionalInt.of(0), depth,
            OptionalDouble.of(FAR_DEPTH));
    }

    public static RenderPass area(String label, RenderTarget target, Optional<Vector4fc> clear, PortalViewport viewport) {
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        if (clear.isPresent()) {
            Vector4fc color = clear.get();
            encoder.clearColorAndDepthTextures(target.getColorTexture(), ARGB.colorFromFloat(color.w(), color.x(), color.y(), color.z()),
                target.getDepthTexture(), FAR_DEPTH, viewport.x(), viewport.y(), viewport.width(), viewport.height());
        }
        RenderPass pass = encoder.createRenderPass(() -> label, target.getColorTextureView(), OptionalInt.empty(), target.getDepthTextureView(),
            OptionalDouble.empty());
        pass.enableScissor(viewport.x(), viewport.y(), viewport.width(), viewport.height());
        return pass;
    }

    public static GpuBufferSlice transform(Matrix4f modelView) {
        return RenderSystem.getDynamicUniforms().writeTransform(modelView, WHITE, NO_OFFSET, IDENTITY);
    }

    public static GpuBufferSlice transform(Matrix4f modelView, Vector4f color) {
        return RenderSystem.getDynamicUniforms().writeTransform(modelView, color, NO_OFFSET, IDENTITY);
    }

    public static GpuBufferSlice transform(DynamicUniforms.Transform transform) {
        return RenderSystem.getDynamicUniforms().writeTransform(transform.modelView(), transform.colorModulator(), transform.modelOffset(),
            transform.textureMatrix());
    }

    public static BufferBuilder quads(ByteBufferBuilder allocation, VertexFormat format) {
        return new BufferBuilder(allocation, VertexFormat.Mode.QUADS, format);
    }
}
