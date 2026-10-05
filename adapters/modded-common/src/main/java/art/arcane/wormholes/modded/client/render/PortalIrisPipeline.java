package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.modded.mixin.client.IrisPortalRenderingAccess;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Consumer;
import java.util.function.Supplier;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
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
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

public final class PortalIrisPipeline implements AutoCloseable {
    private final ProgramSet programs;
    private final ProjectionMatrixBuffer shadowProjection = new ProjectionMatrixBuffer("Wormholes destination shadow");
    private final PortalSharedShadows sharedShadows;
    private final List<GlFramebuffer> sharedFramebuffers = new ArrayList<>();
    private final IrisRenderingPipeline pipeline;
    private final Supplier<WorldRenderingPhase> phaseGetter;
    private final Consumer<WorldRenderingPhase> phaseSetter;
    private final PortalIrisSettings settings;
    private final PortalTerrainMaterials materials;
    private final PortalIrisShaderLoading loading;
    private PortalShaderContext.View lastView;
    private final PortalIrisHistory history = new PortalIrisHistory();
    private boolean initialized;

    PortalIrisPipeline(Request request) {
        sharedShadows = request.shadows();
        ShaderPack pack = Iris.getCurrentPack().orElseThrow();
        NamespacedId dimension = request.dimension();
        programs = pack.getProgramSet(dimension);
        IrisRenderingPipeline created = null;
        try (PortalIrisFrame frame = PortalIrisFrame.building(request.target());
             PortalIrisHistory.Scope histories = history.constructing();
             PortalIrisShaderLoading.Scope deferred = PortalIrisShaderLoading.constructing();
             PortalSharedShadows.Construction shadows = sharedShadows == null ? null : sharedShadows.constructing()) {
            try {
                try (PortalIrisShaderStages.Scope stages = PortalIrisShaderStages.destination()) {
                    created = new IrisRenderingPipeline(programs);
                }
                pipeline = created;
                phaseGetter = pipeline::getPhase;
                phaseSetter = pipeline::setPhase;
                loading = deferred.loading();
                loading.attach(pipeline, programs);
                request.materials().apply(pack);
                ((IrisPortalRenderingAccess) pipeline).wormholes$initializedBlockIds(true);
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
            try {
                history.close();
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

    boolean ready() {
        return loading.ready();
    }

    boolean warm(PortalShaderContext.View view) {
        lastView = view;
        try (PortalIrisFrame frame = new PortalIrisFrame(view);
             PortalIrisHistory.Scope histories = history.constructing();
             PortalIrisShaderStages.Scope stages = PortalIrisShaderStages.destination();
             PortalSharedShadows.Construction shadows = sharedShadows == null ? null : sharedShadows.constructing()) {
            try {
                frame.pipeline(pipeline, settings);
                boolean ready = loading.advance();
                if (ready) {
                    PortalIrisResources.allocated(programs, ((IrisPortalRenderingAccess) pipeline).wormholes$renderTargets());
                }
                return ready;
            } finally {
                if (shadows != null) {
                    sharedFramebuffers.addAll(shadows.framebuffers());
                }
            }
        }
    }

    void released() {
        initialized = false;
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
            if (ready()) {
                PortalIrisResources.allocated(programs, ((IrisPortalRenderingAccess) pipeline).wormholes$renderTargets());
            }
            if (ready() && !initialized) {
                history.reset();
                ((PortalDeferredShaderPipeline) pipeline).wormholes$resetShaders();
                initialized = true;
            }
            pipeline.setPhase(WorldRenderingPhase.NONE);
            return frame;
        } catch (RuntimeException | Error failure) {
            frame.close();
            throw failure;
        }
    }

    void resize() {
        if (lastView == null || !ready()) {
            return;
        }
        boolean previousInitialization = initialized;
        initialized = true;
        try (PortalIrisFrame frame = begin(lastView)) {
            prepare();
            finish();
        } finally {
            initialized = previousInitialization;
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
            programs.getPackDirectives().getShadowDirectives(), shadowProjection, camera, phaseGetter, phaseSetter));
    }

    void prepare() {
        ((IrisPortalRenderingAccess) pipeline).wormholes$prepareRenderer().renderAll();
        pipeline.setPhase(WorldRenderingPhase.SKY);
    }

    CompiledRenderPipeline terrain(ChunkSectionLayer layer, boolean reflected) {
        pipeline.setPhase(PortalIrisTerrain.phase(layer));
        return PortalIrisTerrain.get(layer, reflected);
    }

    void endTerrain() {
        pipeline.setPhase(WorldRenderingPhase.NONE);
    }

    void translucents() {
        pipeline.beginHand();
        pipeline.beginTranslucents();
    }

    void finish() {
        pipeline.finalizeLevelRendering();
        if (ready()) {
            PortalIrisResources.allocated(programs, ((IrisPortalRenderingAccess) pipeline).wormholes$renderTargets());
        }
    }

    @Override
    public void close() {
        loading.close();
        history.close();
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

    record Request(NamespacedId dimension, TextureTarget target, long materialRevision, PortalSharedShadows shadows, PortalIrisMaterials materials) {
    }
}
