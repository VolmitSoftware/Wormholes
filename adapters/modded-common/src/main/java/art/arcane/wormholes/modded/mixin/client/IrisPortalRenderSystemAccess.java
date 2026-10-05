package art.arcane.wormholes.modded.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import org.joml.Matrix4fStack;
import org.spongepowered.asm.mixin.Mutable;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.backend.opengl.GlProgram;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;

@Mixin(value = RenderSystem.class, priority = 900)
public interface IrisPortalRenderSystemAccess {
    @Accessor(value = "iris$overrides", remap = false)
    static Map<CompiledRenderPipeline, Map<GlProgram, Map<List<VertexFormat>, FrontendRenderPipeline>>> wormholes$overrides() {
        throw new UnsupportedOperationException();
    }
    @Mutable
    @Accessor("modelViewStack")
    static void wormholes$modelView(Matrix4fStack value) {
        throw new UnsupportedOperationException();
    }

    @Accessor("savedProjectionMatrixBuffer")
    static GpuBufferSlice wormholes$savedProjection() {
        throw new UnsupportedOperationException();
    }

    @Accessor("savedProjectionMatrixBuffer")
    static void wormholes$savedProjection(GpuBufferSlice value) {
        throw new UnsupportedOperationException();
    }

    @Accessor("savedProjectionType")
    static ProjectionType wormholes$savedProjectionType() {
        throw new UnsupportedOperationException();
    }

    @Accessor("savedProjectionType")
    static void wormholes$savedProjectionType(ProjectionType value) {
        throw new UnsupportedOperationException();
    }
}
