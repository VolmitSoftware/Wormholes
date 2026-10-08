package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.wormholes.modded.mixin.client.IrisPortalRenderingAccess;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.helpers.MatrixUtils;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.samplers.IrisSamplers;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.vertices.ImmediateState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;

public final class IrisMeshFrame implements AutoCloseable {
    private final IrisRenderingPipeline pipeline;
    private final boolean skipExtension;
    private final boolean bypass;
    private final boolean renderingLevel;
    private final boolean extendedFormat;
    private boolean modelView;
    private boolean clipping;

    private IrisMeshFrame(IrisRenderingPipeline pipeline) {
        this.pipeline = pipeline;
        skipExtension = ImmediateState.skipExtension.get();
        bypass = ImmediateState.bypass;
        renderingLevel = ImmediateState.isRenderingLevel;
        extendedFormat = ImmediateState.renderWithExtendedVertexFormat;
    }

    public static boolean available() {
        return Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline;
    }

    public static IrisMeshFrame open(View view) {
        if (!(Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline)) {
            throw new IllegalStateException("No Iris shader pipeline is selected for the destination view");
        }
        IrisMeshFrame frame = new IrisMeshFrame(pipeline);
        try {
            frame.begin(view);
        } catch (RuntimeException | Error failure) {
            try {
                frame.restore();
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        return frame;
    }

    public static void warm() {
        IrisMeshTerrain.warm();
    }

    public static GpuSampler terrainSampler(int anisotropy) {
        return IrisSamplers.getTerrainCache(anisotropy);
    }

    public void sky(Runnable sky) {
        pipeline.setPhase(WorldRenderingPhase.CUSTOM_SKY);
        try {
            sky.run();
        } finally {
            pipeline.setPhase(WorldRenderingPhase.NONE);
        }
    }

    public void content(Matrix4fc view) {
        RenderSystem.getModelViewStack().set(view);
    }

    public CompiledRenderPipeline terrain(ChunkSectionLayer layer, boolean reflected) {
        pipeline.setPhase(IrisMeshTerrain.phase(layer));
        return IrisMeshTerrain.compiled(layer, reflected);
    }

    public void endTerrain() {
        pipeline.setPhase(WorldRenderingPhase.NONE);
    }

    public void translucents() {
        pipeline.beginHand();
        pipeline.beginTranslucents();
    }

    @Override
    public void close() {
        try {
            pipeline.finalizeLevelRendering();
        } finally {
            restore();
        }
    }

    private void begin(View view) {
        IrisLayerUniforms.transition();
        ImmediateState.skipExtension.set(false);
        ImmediateState.bypass = false;
        ImmediateState.isRenderingLevel = true;
        ImmediateState.renderWithExtendedVertexFormat = true;
        CapturedRenderingState state = CapturedRenderingState.INSTANCE;
        state.setGbufferModelView(view.shaderView());
        state.setGbufferProjection(MatrixUtils.undoRevZ(new Matrix4f(view.projection())));
        state.setFogColor(view.fogColor().x(), view.fogColor().y(), view.fogColor().z());
        state.setTickDelta(view.partialTicks());
        Matrix4fStack stack = RenderSystem.getModelViewStack();
        stack.pushMatrix();
        modelView = true;
        stack.set(view.shaderView());
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(main.getColorTexture(),
            new Vector4f(view.fogColor().x(), view.fogColor().y(), view.fogColor().z(), 0.0F), main.getDepthTexture(), 0.0D);
        pipeline.beginLevelRendering();
        pipeline.setPhase(WorldRenderingPhase.NONE);
        ((IrisPortalRenderingAccess) pipeline).wormholes$prepareRenderer().renderAll();
        pipeline.onBeginClear();
        pipeline.setPhase(WorldRenderingPhase.NONE);
        IrisClipPlanes.push(view.clipPlane());
        clipping = true;
    }

    private void restore() {
        try {
            if (clipping) {
                clipping = false;
                IrisClipPlanes.pop();
            }
            if (modelView) {
                modelView = false;
                RenderSystem.getModelViewStack().popMatrix();
            }
            ProgramUniforms.clearActiveUniforms();
            ProgramSamplers.clearActiveSamplers();
        } finally {
            ImmediateState.skipExtension.set(skipExtension);
            ImmediateState.bypass = bypass;
            ImmediateState.isRenderingLevel = renderingLevel;
            ImmediateState.renderWithExtendedVertexFormat = extendedFormat;
            IrisLayerUniforms.transition();
        }
    }

    public record View(Matrix4fc shaderView, Matrix4fc projection, Vector4fc fogColor, Vector4fc clipPlane, float partialTicks) {
    }
}
