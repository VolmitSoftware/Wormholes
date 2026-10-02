package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.modded.mixin.client.IrisPortalRenderingAccess;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.OptionalDouble;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import org.joml.Vector4f;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.shaderpack.DimensionId;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.state.level.CameraRenderState;

public final class PortalIrisPipeline implements AutoCloseable {
    private final ProgramSet programs;
    private final ProjectionMatrixBuffer shadowProjection = new ProjectionMatrixBuffer("Wormholes destination shadow");
    private final PortalSharedShadows sharedShadows;
    private final List<GlFramebuffer> sharedFramebuffers = new ArrayList<>();
    private final ShaderPack pack;
    private final NamespacedId dimension;
    private final IrisRenderingPipeline pipeline;
    private final PortalIrisSettings settings;
    private final PortalTerrainMaterials materials;
    private PortalShaderContext.View lastView;

    PortalIrisPipeline(Request request) {
        lastView = request.view();
        sharedShadows = request.shadows();
        pack = Iris.getCurrentPack().orElseThrow();
        dimension = dimension(pack, request.view().environment());
        programs = pack.getProgramSet(dimension);
        IrisRenderingPipeline created = null;
        try (PortalIrisFrame frame = new PortalIrisFrame(request.view());
             PortalSharedShadows.Construction shadows = sharedShadows == null ? null : sharedShadows.constructing()) {
            try {
                try (PortalIrisShaderStages.Scope stages = PortalIrisShaderStages.destination()) {
                    created = new IrisRenderingPipeline(programs);
                }
                pipeline = created;
                request.materials().apply(pack);
                ((IrisPortalRenderingAccess) pipeline).wormholes$initializedBlockIds(true);
                PortalIrisResources.allocated(programs, ((IrisPortalRenderingAccess) pipeline).wormholes$renderTargets());
                settings = PortalIrisSettings.capture();
                materials = new PortalTerrainMaterials(true, request.materials().terrain(), request.materialRevision(),
                    new PortalTerrainMaterials.Lighting(settings.ambientOcclusion(), settings.directionalShading(), settings.separateAo()));

            } finally {
                if (shadows != null) {
                    sharedFramebuffers.addAll(shadows.framebuffers());
                }
            }
        } catch (RuntimeException | Error failure) {
            try {
                try {
                    if (created != null) {
                        created.destroy();
                    }
                } finally {
                    try {
                        shadowProjection.close();
                    } finally {
                        if (sharedShadows != null) {
                            sharedShadows.release(sharedFramebuffers);
                        }
                    }
                }
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public static NamespacedId dimension(ShaderPack pack, ClientViewEnvironment environment) {
        NamespacedId requested = new NamespacedId(environment.world().dimensionKey());
        if (pack.getDimensionMap().containsKey(requested)) {
            return requested;
        }
        return switch (environment.sky().skybox()) {
            case END -> DimensionId.END;
            case OVERWORLD -> DimensionId.OVERWORLD;
            case NONE -> DimensionId.NETHER;
        };
    }

    PortalTerrainMaterials materials() {
        return materials;
    }

    boolean matches(ClientViewEnvironment environment) {
        return Iris.getCurrentPack().orElse(null) == pack && dimension.equals(dimension(pack, environment));
    }

    PortalIrisFrame begin(PortalShaderContext.View view) {
        lastView = view;
        PortalIrisFrame frame = new PortalIrisFrame(view);
        try {
            frame.pipeline(pipeline, settings);
            try (RenderPass clear = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Wormholes shader view clear",
                view.target().getColorTextureView(), Optional.of(new Vector4f(0, 0, 0, 1)),
                view.target().getDepthTextureView(), OptionalDouble.of(0))) {
            }
            try (PortalSharedShadows.Construction shadows = sharedShadows == null ? null : sharedShadows.constructing()) {
                try {
                    pipeline.beginLevelRendering();
                } finally {
                    if (shadows != null) {
                        sharedFramebuffers.addAll(shadows.framebuffers());
                    }
                }
            }
            PortalIrisResources.allocated(programs, ((IrisPortalRenderingAccess) pipeline).wormholes$renderTargets());
            pipeline.setPhase(WorldRenderingPhase.NONE);
            return frame;
        } catch (RuntimeException | Error failure) {
            frame.close();
            throw failure;
        }
    }

    void resize() {
        try (PortalIrisFrame frame = begin(lastView)) {
            prepare();
            finish();
        }
    }

    PortalIrisShadowFrame shadows(CameraRenderState camera) {
        IrisPortalRenderingAccess access = (IrisPortalRenderingAccess) pipeline;
        ShadowRenderer renderer = access.wormholes$shadowRenderer();
        ShadowRenderTargets targets = access.wormholes$shadowTargets();
        if (renderer == null || targets == null) {
            return null;
        }
        return new PortalIrisShadowFrame(new PortalIrisShadowFrame.Request(pipeline, renderer, targets,
            programs.getPackDirectives().getShadowDirectives(), shadowProjection, camera));
    }

    void prepare() {
        ((IrisPortalRenderingAccess) pipeline).wormholes$prepareRenderer().renderAll();
        pipeline.setPhase(WorldRenderingPhase.SKY);
    }

    void phase(WorldRenderingPhase phase) {
        pipeline.setPhase(phase);
    }

    void translucents() {
        pipeline.beginHand();
        pipeline.beginTranslucents();
    }

    void finish() {
        pipeline.finalizeLevelRendering();
        PortalIrisResources.allocated(programs, ((IrisPortalRenderingAccess) pipeline).wormholes$renderTargets());
    }

    IrisRenderingPipeline pipeline() {
        return pipeline;
    }

    @Override
    public void close() {
        try (PortalShaderScope scope = PortalShaderScope.rendering();
             PortalFramebufferScope framebuffer = PortalFramebufferScope.capture();
             PortalTextureScope textures = new PortalTextureScope()) {
            try {
                PortalIrisOverrides.release(pipeline);
            } finally {
                try {
                    pipeline.destroy();
                } finally {
                    try {
                        shadowProjection.close();
                    } finally {
                        if (sharedShadows != null) {
                            sharedShadows.release(sharedFramebuffers);
                        }
                    }
                }
            }
        }
    }

    record Request(PortalShaderContext.View view, long materialRevision, PortalSharedShadows shadows, PortalIrisMaterials materials) {
    }
}
