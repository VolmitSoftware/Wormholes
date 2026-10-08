package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.modded.mixin.client.PreparedLevelAccess;
import art.arcane.wormholes.modded.mixin.client.IrisPortalDhAccess;
import art.arcane.wormholes.modded.mixin.client.IrisPortalRenderingAccess;
import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.DimensionId;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

public final class PortalIrisMainPipelines {
    private static final Logger LOGGER = LoggerFactory.getLogger(PortalIrisMainPipelines.class);
    private static final Map<WorldRenderingPipeline, PortalIrisSettings> SETTINGS = new IdentityHashMap<>();
    private static final Map<IrisRenderingPipeline, Entry> HISTORIES = new IdentityHashMap<>();
    private static final long PREPARING_NANOS = 250_000_000L;
    private static final List<NamespacedId> STANDARD_DIMENSIONS = List.of(DimensionId.OVERWORLD, DimensionId.NETHER, DimensionId.END);
    private static Entry pending;
    private static ClientLevel destination;
    private static ClientLevel source;
    private static NamespacedId sourceDimension;
    private static NamespacedId destinationDimension;
    private static ShaderPack destinationPack;
    private static ClientLevel failed;
    private static Handoff handoff;
    private static boolean constructing;
    private static long preparedAt;

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
        preparedAt = System.nanoTime();
        PortalShaderWarmup warmups = PortalShaderWarmup.shared();
        if (!warmups.permit()) {
            return false;
        }
        try (World world = new World(level)) {
            if (pending == null) {
                try {
                    pending = create(pack, dimension);
                } finally {
                    warmups.exhaust();
                }
                return false;
            }
            manager.wormholes$mainPipeline(pending.pipeline);
            pending.settings.apply();
            long started = warmups.start();
            try (PortalIrisHistory.Scope histories = pending.history.constructing();
                 PortalIrisShaderStages.Scope stages = PortalIrisShaderStages.destination()) {
                if (!pending.loading.advance(warmups.remaining())) {
                    return false;
                }
            } finally {
                warmups.spend(started);
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

    static boolean preparing() {
        return pending != null && !pending.registered && System.nanoTime() - preparedAt < PREPARING_NANOS;
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
                ShaderPack pack = Iris.getCurrentPack().orElse(null);
                Entry entry = new Entry(new Construction(pack, pipeline, null, history, PortalIrisSettings.capture()));
                entry.registered = true;
                entry.reset = false;
                HISTORIES.put(pipeline, entry);
                if (pack != null) {
                    prepareProgramSets(pack);
                }
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
            clearPending();
        } catch (RuntimeException | Error cleanup) {
            if (failure == null) {
                failure = cleanup;
            } else {
                failure.addSuppressed(cleanup);
            }
        }
        SETTINGS.clear();
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

    private static void prepareProgramSets(ShaderPack pack) {
        Set<NamespacedId> dimensions = new LinkedHashSet<>(STANDARD_DIMENSIONS);
        dimensions.addAll(pack.getDimensionMap().keySet());
        for (NamespacedId dimension : dimensions) {
            try {
                pack.getProgramSet(dimension);
            } catch (RuntimeException failure) {
                LOGGER.error("Unable to read the shader programs for dimension {}", dimension, failure);
            }
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
