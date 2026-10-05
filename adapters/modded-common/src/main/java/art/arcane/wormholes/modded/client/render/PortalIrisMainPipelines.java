package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.modded.mixin.client.PreparedLevelAccess;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.util.Direction;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.util.GameRendererStorage;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import java.util.Optional;
import java.util.OptionalDouble;
import art.arcane.wormholes.modded.mixin.client.IrisPortalDhAccess;
import art.arcane.wormholes.modded.mixin.client.IrisPortalRenderingAccess;
import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Supplier;

public final class PortalIrisMainPipelines {
    private static final Logger LOGGER = LoggerFactory.getLogger(PortalIrisMainPipelines.class);
    private static final Map<WorldRenderingPipeline, PortalIrisSettings> SETTINGS = new IdentityHashMap<>();
    private static final Map<IrisRenderingPipeline, Entry> HISTORIES = new IdentityHashMap<>();
    private static Entry pending;
    private static ClientLevel destination;
    private static ClientLevel source;
    private static NamespacedId sourceDimension;
    private static NamespacedId destinationDimension;
    private static ShaderPack destinationPack;
    private static ClientLevel failed;
    private static Handoff handoff;
    private static boolean constructing;
    private static PortalIrisHand preparedHand;

    private PortalIrisMainPipelines() {
    }

    public static boolean prepare(ClientLevel level) {
        RenderSystem.assertOnRenderThread();
        ShaderPack pack = Iris.getCurrentPack().orElse(null);
        if (pack == null || level == Minecraft.getInstance().level) {
            return true;
        }
        if (level == failed) {
            return false;
        }
        PortalMainPipelineAccess manager = manager();
        WorldRenderingPipeline current = Iris.getPipelineManager().getPipelineNullable();
        if (current != null) {
            SETTINGS.put(current, PortalIrisSettings.capture());
        }
        NamespacedId dimension;
        try (World world = new World(level)) {
            dimension = Iris.getCurrentDimension();
        }
        if (destination != level || pending != null && pending.pack != pack) {
            clearPending();
            source = Minecraft.getInstance().level;
            sourceDimension = Iris.getCurrentDimension();
            destination = level;
            destinationDimension = dimension;
            destinationPack = pack;
        }
        WorldRenderingPipeline retained = manager.wormholes$mainPipelines().get(dimension);
        if (retained != null) {
            return true;
        }
        try (World world = new World(level)) {
            if (pending == null) {
                pending = create(pack, dimension);
            }
            manager.wormholes$mainPipeline(pending.pipeline);
            pending.settings.apply();
            try (PortalIrisHistory.Scope histories = pending.history.constructing();
                 PortalIrisShaderStages.Scope stages = PortalIrisShaderStages.destination()) {
                if (!pending.loading.advance()) {
                    return false;
                }
            }
            pending.settings = PortalIrisSettings.capture();
            trim(manager, current);
            manager.wormholes$mainPipelines().put(dimension, pending.pipeline);
            SETTINGS.put(pending.pipeline, pending.settings);
            HISTORIES.put(pending.pipeline, pending);
            pending.registered = true;
            return true;
        } catch (RuntimeException failure) {
            failed = level;
            LOGGER.error("Unable to prepare the normal Iris pipeline for dimension {}", dimension, failure);
            clearPending();
            return false;
        }
    }

    static int nativeFrame() {
        return SystemTimeUniforms.COUNTER.getAsInt();
    }

    static Object nativePipeline(ClientLevel level) {
        return retainedPipeline(level);
    }

    static boolean warmNative(NativeDraw draw) {
        IrisRenderingPipeline pipeline = (IrisRenderingPipeline) retainedPipeline(draw.level());
        if (pipeline == null) {
            return false;
        }
        Entry entry = HISTORIES.get(pipeline);
        Minecraft minecraft = Minecraft.getInstance();
        Matrix4fc projection = ((GameRendererStorage) minecraft.gameRenderer).sodium$getProjectionMatrix();
        if (projection == null) {
            return false;
        }
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        TextureTarget target = renderer.nativeTravelTarget();
        ClientViewEnvironment environment = draw.environment().withTransform(new ClientViewEnvironment.Transform(
            Direction.E, Direction.U, Direction.S, new GeometryVector(0, 0, 0)));
        PortalShaderCamera camera = new PortalShaderCamera(environment, draw.camera());
        PortalShaderContext.View view = new PortalShaderContext.View(environment, camera, target,
            draw.camera().viewRotationMatrix, projection);
        IrisPortalRenderingAccess access = (IrisPortalRenderingAccess) pipeline;
        DrawState lifecycle = new DrawState(access.wormholes$renderingWorld(), access.wormholes$mainBound(),
            pipeline.isBeforeTranslucent);
        GpuBufferSlice previousProjection = RenderSystem.getProjectionMatrixBuffer();
        GpuBufferSlice previousFog = RenderSystem.getShaderFog();
        ProjectionType previousType = RenderSystem.getProjectionType();
        try (World world = new World(draw.level());
             PortalIrisFrame frame = new PortalIrisFrame(view)) {
            frame.pipeline(pipeline, entry.settings);
            try {
                renderer.prepareNativeSky(environment, draw.camera());
                try (PortalLightmapScope lightmap = new PortalLightmapScope(renderer.nativeEnvironment().lightmap())) {
                    RenderSystem.setProjectionMatrix(renderer.nativeProjection(projection), previousType);
                    RenderSystem.setShaderFog(renderer.nativeEnvironment().fogBuffer());
                    pipeline.beginLevelRendering();
                    access.wormholes$prepareRenderer().renderAll();
                    if (draw.stage() == ClientSodiumTerrain.WarmStage.SKY) {
                        pipeline.setPhase(WorldRenderingPhase.SKY);
                        renderer.renderNativeSky();
                    } else if (draw.stage() == ClientSodiumTerrain.WarmStage.POST) {
                        pipeline.beginHand();
                        pipeline.beginTranslucents();
                        pipeline.setPhase(WorldRenderingPhase.NONE);
                        pipeline.finalizeLevelRendering();
                    } else if (draw.stage() == ClientSodiumTerrain.WarmStage.HAND_SOLID
                        || draw.stage() == ClientSodiumTerrain.WarmStage.HAND_TRANSLUCENT) {
                        if (preparedHand == null) {
                            preparedHand = new PortalIrisHand();
                        }
                        if (!preparedHand.draw(pipeline, draw.camera(), draw.stage() == ClientSodiumTerrain.WarmStage.HAND_TRANSLUCENT)) {
                            return false;
                        }
                    } else {
                        warmTerrain(draw, pipeline, projection, target);
                    }
                }
            } finally {
                entry.reset = true;
                lifecycle.restore(pipeline);
            }
        } finally {
            RenderSystem.setProjectionMatrix(previousProjection, previousType);
            RenderSystem.setShaderFog(previousFog);
            if (renderer.nativeEnvironment() != null) {
                renderer.nativeEnvironment().endFrame();
            }
        }
        return true;
    }

    private static void warmTerrain(NativeDraw draw, IrisRenderingPipeline pipeline, Matrix4fc projection, TextureTarget target) {
        NativeTerrain terrain = switch (draw.stage()) {
            case SOLID -> new NativeTerrain(DefaultTerrainRenderPasses.SOLID, WorldRenderingPhase.TERRAIN_SOLID);
            case CUTOUT -> new NativeTerrain(DefaultTerrainRenderPasses.CUTOUT, WorldRenderingPhase.TERRAIN_CUTOUT);
            case TRANSLUCENT -> new NativeTerrain(DefaultTerrainRenderPasses.TRANSLUCENT, WorldRenderingPhase.TERRAIN_TRANSLUCENT);
            default -> throw new IllegalArgumentException("Invalid native terrain warm stage");
        };
        if (draw.stage() == ClientSodiumTerrain.WarmStage.TRANSLUCENT) {
            pipeline.beginHand();
            pipeline.beginTranslucents();
        }
        pipeline.setPhase(terrain.phase());
        ChunkRenderMatrices matrices = new ChunkRenderMatrices(projection, draw.camera().viewRotationMatrix);
        double x = draw.camera().pos.x;
        double y = draw.camera().pos.y;
        double z = draw.camera().pos.z;
        draw.renderer().prepareChunkRendering(matrices, x, y, z);
        Minecraft minecraft = Minecraft.getInstance();
        int anisotropy = minecraft.options.textureFiltering().get() == TextureFilteringMethod.ANISOTROPIC
            ? minecraft.options.maxAnisotropyValue() : 1;
        FogParameters fog = new FogParameters(draw.environment().fog().color().red(), draw.environment().fog().color().green(),
            draw.environment().fog().color().blue(), 1, Float.NaN, Float.NaN, 0,
            (minecraft.options.getEffectiveRenderDistance() + 1) * 16.0f);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Wormholes prepared native terrain",
            target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
            draw.renderer().renderLayer(matrices, terrain.pass(), x, y, z, fog, PortalIrisTerrain.sampler(anisotropy), pass, null);
        }
    }

    record DrawState(boolean renderingWorld, boolean mainBound, boolean beforeTranslucent) {
        void restore(IrisRenderingPipeline pipeline) {
            IrisPortalRenderingAccess access = (IrisPortalRenderingAccess) pipeline;
            try {
                if (access.wormholes$phase() != WorldRenderingPhase.NONE) {
                    pipeline.setPhase(WorldRenderingPhase.NONE);
                }
                pipeline.removePhaseIfNeeded();
            } finally {
                access.wormholes$renderingWorld(renderingWorld);
                access.wormholes$mainBound(mainBound);
                pipeline.isBeforeTranslucent = beforeTranslucent;
            }
        }
    }

    private record NativeTerrain(TerrainRenderPass pass, WorldRenderingPhase phase) {
    }

    record NativeDraw(ClientLevel level, ClientViewEnvironment environment, CameraRenderState camera,
                      SodiumWorldRenderer renderer, ClientSodiumTerrain.WarmStage stage) {
    }

    public static boolean ready(ClientLevel level) {
        if (Iris.getCurrentPack().isEmpty() || level == Minecraft.getInstance().level) {
            return true;
        }
        return destinationPack == Iris.getCurrentPack().orElse(null)
            && (destination == level && manager().wormholes$mainPipelines().containsKey(destinationDimension)
                || source == level && manager().wormholes$mainPipelines().containsKey(sourceDimension));
    }

    public static Handoff handoff(ClientLevel level) {
        RenderSystem.assertOnRenderThread();
        ShaderPack pack = Iris.getCurrentPack().orElse(null);
        NamespacedId dimension = level == source ? sourceDimension : destinationDimension;
        WorldRenderingPipeline pipeline = pack != null && ready(level) && dimension != null
            ? manager().wormholes$mainPipelines().get(dimension) : null;
        return new Handoff(new Attachment(level, pipeline, pack));
    }

    public static Handoff authoritativeHandoff(ClientLevel level) {
        RenderSystem.assertOnRenderThread();
        return new Handoff(new Attachment(level, retainedPipeline(level), Iris.getCurrentPack().orElse(null)));
    }

    static boolean authoritativeTerrainCompatible(ClientLevel level) {
        if (Iris.getCurrentPack().isEmpty()) {
            return true;
        }
        PortalIrisSettings settings = SETTINGS.get(retainedPipeline(level));
        return settings != null && settings.terrainCompatible(PortalIrisSettings.capture());
    }

    static boolean terrainCompatible(ClientLevel level) {
        if (Iris.getCurrentPack().isEmpty()) {
            return true;
        }
        if (!ready(level)) {
            return false;
        }
        NamespacedId dimension = level == source ? sourceDimension : level == destination ? destinationDimension : Iris.getCurrentDimension();
        PortalIrisSettings settings = SETTINGS.get(manager().wormholes$mainPipelines().get(dimension));
        return settings != null && settings.terrainCompatible(PortalIrisSettings.capture());
    }

    public static boolean retainDimensionChange() {
        if (handoff == null || Minecraft.getInstance().level != handoff.destination
            || handoff.pack != Iris.getCurrentPack().orElse(null) || handoff.pipeline == null
            || manager().wormholes$mainPipelines().get(Iris.getCurrentDimension()) != handoff.pipeline) {
            return false;
        }
        manager().wormholes$advanceMainVersion();
        return true;
    }

    public static WorldRenderingPipeline capture(Supplier<WorldRenderingPipeline> factory) {
        if (constructing || PortalShaderContext.target() != null) {
            return factory.get();
        }
        PortalIrisHistory history = new PortalIrisHistory();
        WorldRenderingPipeline created = null;
        try (PortalIrisHistory.Scope scope = history.constructing()) {
            created = factory.get();
            if (created instanceof IrisRenderingPipeline pipeline) {
                Entry entry = new Entry(new Construction(Iris.getCurrentPack().orElse(null), pipeline, null, history, PortalIrisSettings.capture()));
                entry.registered = true;
                entry.reset = false;
                HISTORIES.put(pipeline, entry);
            } else {
                history.close();
            }
            return created;
        } catch (RuntimeException | Error failure) {
            try {
                history.close();
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public static void selected(NamespacedId dimension, WorldRenderingPipeline pipeline) {
        if (!(pipeline instanceof IrisRenderingPipeline) || constructing || PortalShaderContext.target() != null) {
            return;
        }
        PortalIrisSettings settings = SETTINGS.get(pipeline);
        if (settings != null) {
            boolean reloadRequired = WorldRenderingSettings.INSTANCE.isReloadRequired()
                || !settings.terrainCompatible(PortalIrisSettings.capture());
            settings.apply();
            if (reloadRequired && Minecraft.getInstance().levelExtractor != null) {
                Minecraft.getInstance().levelExtractor.allChanged();
            }
            WorldRenderingSettings.INSTANCE.clearReloadRequired();
        }
        SETTINGS.put(pipeline, PortalIrisSettings.capture());
        Entry entry = pipeline instanceof IrisRenderingPipeline iris ? HISTORIES.get(iris) : null;
        if (entry != null && handoff != null && handoff.pipeline != null) {
            entry.reset = true;
        }
    }

    public static void drawn(IrisRenderingPipeline pipeline) {
        if (PortalShaderContext.target() == null && !constructing) {
            ClientSodiumTerrain.mainDrawn(pipeline);
        }
    }

    public static void begin(IrisRenderingPipeline pipeline) {
        if (PortalShaderContext.target() != null || constructing) {
            return;
        }
        Entry entry = HISTORIES.get(pipeline);
        if (entry != null && entry.reset) {
            entry.history.reset();
            ((PortalDeferredShaderPipeline) pipeline).wormholes$resetShaders();
            entry.reset = false;
        }
        SETTINGS.put(pipeline, PortalIrisSettings.capture());
    }

    public static void clearPending() {
        Entry entry = pending;
        pending = null;
        destination = null;
        source = null;
        sourceDimension = null;
        destinationDimension = null;
        destinationPack = null;
        if (entry != null && !entry.registered) {
            entry.close();
        }
    }

    public static void destroyed() {
        Throwable failure = null;
        try {
            if (!ClientSodiumTerrain.retainUnshadedHandoff()) {
                ClientSodiumTerrain.clear();
            }
        } catch (RuntimeException | Error cleanup) {
            failure = cleanup;
        }
        try {
            clearPending();
        } catch (RuntimeException | Error cleanup) {
            if (failure == null) {
                failure = cleanup;
            } else {
                failure.addSuppressed(cleanup);
            }
        }
        SETTINGS.clear();
        if (preparedHand != null) {
            PortalIrisHand hand = preparedHand;
            preparedHand = null;
            try {
                hand.close();
            } catch (RuntimeException | Error cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        for (Entry entry : HISTORIES.values()) {
            try {
                entry.releaseHistory();
            } catch (RuntimeException | Error cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        HISTORIES.clear();
        failed = null;
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private static PortalMainPipelineAccess manager() {
        return (PortalMainPipelineAccess) Iris.getPipelineManager();
    }

    private static Entry create(ShaderPack pack, NamespacedId dimension) {
        PortalIrisHistory history = new PortalIrisHistory();
        WorldRenderingPipeline created = null;
        constructing = true;
        try (PortalIrisHistory.Scope histories = history.constructing();
             PortalIrisShaderLoading.Scope loading = PortalIrisShaderLoading.main();
             PortalIrisShaderStages.Scope stages = PortalIrisShaderStages.destination()) {
            created = new IrisRenderingPipeline(pack.getProgramSet(dimension));
            if (!(created instanceof IrisRenderingPipeline pipeline)) {
                throw new IllegalStateException("The normal Iris pipeline did not initialize");
            }
            PortalIrisShaderLoading shaders = loading.loading();
            shaders.attach(pipeline, pack.getProgramSet(dimension));
            new PortalIrisMaterials().apply(pack);
            ((IrisPortalRenderingAccess) pipeline).wormholes$initializedBlockIds(true);
            return new Entry(new Construction(pack, pipeline, shaders, history, PortalIrisSettings.capture()));
        } catch (RuntimeException | Error failure) {
            try {
                if (created != null) {
                    created.destroy();
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
        } finally {
            constructing = false;
        }
    }

    private static void trim(PortalMainPipelineAccess manager, WorldRenderingPipeline current) {
        Iterator<Map.Entry<NamespacedId, WorldRenderingPipeline>> iterator = manager.wormholes$mainPipelines().entrySet().iterator();
        while (iterator.hasNext()) {
            WorldRenderingPipeline pipeline = iterator.next().getValue();
            if (pipeline == current) {
                continue;
            }
            iterator.remove();
            SETTINGS.remove(pipeline);
            Entry entry = pipeline instanceof IrisRenderingPipeline iris ? HISTORIES.remove(iris) : null;
            try {
                pipeline.destroy();
            } finally {
                if (entry != null) {
                    entry.releaseHistory();
                }
            }
        }
    }

    private static WorldRenderingPipeline retainedPipeline(ClientLevel level) {
        ShaderPack pack = Iris.getCurrentPack().orElse(null);
        if (pack == null) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel previous = minecraft.level;
        NamespacedId dimension;
        minecraft.level = level;
        try {
            dimension = Iris.getCurrentDimension();
        } finally {
            minecraft.level = previous;
        }
        WorldRenderingPipeline pipeline = manager().wormholes$mainPipelines().get(dimension);
        Entry entry = pipeline instanceof IrisRenderingPipeline iris ? HISTORIES.get(iris) : null;
        return entry != null && entry.registered && !entry.closed && entry.pack == pack ? pipeline : null;
    }

    public static final class Handoff implements AutoCloseable {
        private final Handoff previous;
        private final ClientLevel destination;
        private final WorldRenderingPipeline pipeline;
        private final ShaderPack pack;
        private boolean closed;

        private Handoff(Attachment attachment) {
            previous = handoff;
            destination = attachment.level();
            pipeline = attachment.pipeline();
            pack = attachment.pack();
            handoff = this;
            Entry entry = pipeline instanceof IrisRenderingPipeline iris ? HISTORIES.get(iris) : null;
            if (entry != null) {
                entry.reset = true;
            }
        }

        @Override
        public void close() {
            if (!closed) {
                if (handoff != this) {
                    throw new IllegalStateException("Normal Iris handoff scope order");
                }
                closed = true;
                handoff = previous;
            }
        }
    }

    private record Attachment(ClientLevel level, WorldRenderingPipeline pipeline, ShaderPack pack) {
    }

    record Construction(ShaderPack pack, IrisRenderingPipeline pipeline, PortalIrisShaderLoading loading,
                        PortalIrisHistory history, PortalIrisSettings settings) {
    }

    static final class Entry implements AutoCloseable {
        private final ShaderPack pack;
        private final IrisRenderingPipeline pipeline;
        private final PortalIrisShaderLoading loading;
        private final PortalIrisHistory history;
        private PortalIrisSettings settings;
        private boolean registered;
        private boolean reset = true;
        private boolean closed;

        Entry(Construction construction) {
            pack = construction.pack();
            pipeline = construction.pipeline();
            loading = construction.loading();
            history = construction.history();
            settings = construction.settings();
        }

        private void releaseHistory() {
            try {
                if (loading != null) {
                    loading.close();
                }
            } finally {
                history.close();
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                releaseHistory();
            } finally {
                pipeline.destroy();
            }
        }
    }

    private static final class World implements AutoCloseable {
        private final Minecraft minecraft = Minecraft.getInstance();
        private final ClientLevel previousLevel = minecraft.level;
        private final LevelExtractor previousExtractor = minecraft.levelExtractor;
        private final WorldRenderingPipeline previousPipeline = Iris.getPipelineManager().getPipelineNullable();
        private final PortalIrisSettings settings = PortalIrisSettings.capture();
        private final PortalIrisState state = PortalIrisState.capture();
        private final boolean dh = IrisPortalDhAccess.wormholes$incompatible();
        private final PortalTextureScope textures = new PortalTextureScope();
        private final PortalFramebufferScope framebuffer = PortalFramebufferScope.capture();

        private World(ClientLevel level) {
            LevelExtractor extractor = ((PreparedLevelAccess) level).wormholes$extractor();
            ((PortalMainWorldAccess) minecraft).wormholes$mainExtractor(extractor);
            minecraft.level = level;
        }

        @Override
        public void close() {
            minecraft.level = previousLevel;
            ((PortalMainWorldAccess) minecraft).wormholes$mainExtractor(previousExtractor);
            manager().wormholes$mainPipeline(previousPipeline);
            try {
                settings.apply();
                state.apply();
            } finally {
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
                            IrisPortalDhAccess.wormholes$incompatible(dh);
                        }
                    }
                }
            }
        }
    }
}
