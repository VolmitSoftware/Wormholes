package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.modded.mixin.client.IrisPortalPipelineAccess;
import art.arcane.wormholes.modded.mixin.client.IrisPortalDhAccess;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import art.arcane.wormholes.modded.mixin.client.IrisPortalSettingsAccess;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.helpers.MatrixUtils;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.vertices.ImmediateState;
import org.joml.Matrix4f;

final class PortalIrisFrame implements PortalShaderRenderer.Frame {
    private final boolean previousDhIncompatible;
    private final PortalTextureScope textures;
    private final WorldRenderingPipeline previousPipeline;
    private final PortalIrisSettings previousSettings;
    private final PortalIrisState previousState;
    private final Immediate previousImmediate;
    private final PortalShaderContext context;
    private final PortalFramebufferScope framebuffer;

    PortalIrisFrame(PortalShaderContext.View view) {
        this(view, view.target());
    }

    static PortalIrisFrame building(RenderTarget target) {
        return new PortalIrisFrame(null, target);
    }

    private PortalIrisFrame(PortalShaderContext.View view, RenderTarget target) {
        previousDhIncompatible = IrisPortalDhAccess.wormholes$incompatible();
        textures = new PortalTextureScope();
        previousPipeline = Iris.getPipelineManager().getPipelineNullable();
        previousSettings = PortalIrisSettings.capture();
        previousState = PortalIrisState.capture();
        previousImmediate = Immediate.capture();
        framebuffer = PortalFramebufferScope.capture();
        context = view == null ? new PortalShaderContext(target) : new PortalShaderContext(view);
        boolean pushed = false;
        try {
            RenderSystem.getModelViewStack().pushMatrix();
            if (view != null) {
                RenderSystem.getModelViewStack().set(view.modelView());
            }
            pushed = true;
            if (view != null) {
                CapturedRenderingState state = CapturedRenderingState.INSTANCE;
                state.setGbufferModelView(view.modelView());
                state.setGbufferProjection(MatrixUtils.undoRevZ(new Matrix4f(view.projection())));
                state.setFogColor(view.environment().fog().color().red(), view.environment().fog().color().green(),
                    view.environment().fog().color().blue());
            }
            ImmediateState.skipExtension.set(false);
            ImmediateState.bypass = false;
            ImmediateState.isRenderingLevel = true;
            ImmediateState.renderWithExtendedVertexFormat = true;
            ImmediateState.usingTessellation = false;
            ImmediateState.safeToMultiply = false;
            ImmediateState.temporarilyIgnorePass = false;
            ImmediateState.isRenderingBEs = false;
        } catch (RuntimeException | Error failure) {
            try {
                previousState.apply();
                if (pushed) {
                    RenderSystem.getModelViewStack().popMatrix();
                }
            } finally {
                restoreBindings();
            }
            throw failure;
        }
    }

    void pipeline(IrisRenderingPipeline pipeline, PortalIrisSettings settings) {
        settings.apply();
        ((IrisPortalSettingsAccess) WorldRenderingSettings.INSTANCE).wormholes$reloadRequired(false);
        ((IrisPortalPipelineAccess) Iris.getPipelineManager()).wormholes$pipeline(pipeline);
        context.drawing(true);
    }

    @Override
    public void close() {
        try {
            context.drawing(false);
            ((IrisPortalPipelineAccess) Iris.getPipelineManager()).wormholes$pipeline(previousPipeline);
            previousSettings.apply();
            previousState.apply();
        } finally {
            try {
                RenderSystem.getModelViewStack().popMatrix();
            } finally {
                restoreBindings();
            }
        }
    }

    private void restoreBindings() {
        try {
            if (framebuffer != null) {
                framebuffer.close();
            }
        } finally {
            try {
                ProgramUniforms.clearActiveUniforms();
                ProgramSamplers.clearActiveSamplers();
            } finally {
                try {
                    textures.close();
                } finally {
                    try {
                        IrisPortalDhAccess.wormholes$incompatible(previousDhIncompatible);
                    } finally {
                        try {
                            previousImmediate.apply();
                        } finally {
                            context.close();
                        }
                    }
                }
            }
        }
    }

    record Immediate(boolean extension, boolean bypass, boolean level, boolean format, boolean tessellation, boolean routing, boolean ignorePass, boolean blockEntities) {
        static Immediate capture() {
            return new Immediate(ImmediateState.skipExtension.get(), ImmediateState.bypass, ImmediateState.isRenderingLevel,
                ImmediateState.renderWithExtendedVertexFormat, ImmediateState.usingTessellation, ImmediateState.safeToMultiply,
                ImmediateState.temporarilyIgnorePass, ImmediateState.isRenderingBEs);
        }

        void apply() {
            ImmediateState.skipExtension.set(extension);
            ImmediateState.bypass = bypass;
            ImmediateState.isRenderingLevel = level;
            ImmediateState.renderWithExtendedVertexFormat = format;
            ImmediateState.usingTessellation = tessellation;
            ImmediateState.safeToMultiply = routing;
            ImmediateState.temporarilyIgnorePass = ignorePass;
            ImmediateState.isRenderingBEs = blockEntities;
        }
    }

}
