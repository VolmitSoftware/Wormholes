package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.wormholes.modded.client.render.PortalFramebufferScope;
import art.arcane.wormholes.modded.client.render.PortalTextureScope;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.modded.client.render.stencil.PortalViews;
import art.arcane.wormholes.modded.mixin.client.IrisPortalRenderingAccess;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class IrisViewPipelines {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_VIEWS = 12;
    private static final long MAX_IDLE_NANOS = 60_000_000_000L;
    private static final ArrayDeque<Scope> SCOPES = new ArrayDeque<>();
    private static final Set<IrisViewPipelinePath> PENDING = new LinkedHashSet<>();
    private static final Set<IrisViewPipelinePath> PLANNED = new LinkedHashSet<>();
    private static final Set<IrisViewPipelinePath> FAILED = new LinkedHashSet<>();
    private static long creationNanos;
    private static long preparationNanos;
    private static long drawCreationNanos;
    private static long destructions;
    private static long destructionNanos;
    private static long drawMisses;
    private static long drawCreations;
    private static long failedCreations;
    private static int capacityMisses;
    private static final IrisViewPipelineCache<IrisViewPipelinePath, Pipeline> CACHE = new IrisViewPipelineCache<>(
        new IrisViewPipelineCache.Options<>(MAX_VIEWS, IrisViewPipelines::create, IrisViewPipelines::destroy, System::nanoTime));

    private IrisViewPipelines() {
    }

    static Scope open(PortalView view) {
        Scope scope = new Scope(view);
        SCOPES.push(scope);
        return scope;
    }

    public static IrisRenderingPipeline select(NamespacedId dimension) {
        Scope scope = SCOPES.peek();
        if (scope == null) {
            expire();
            return null;
        }
        if (scope.path == null) {
            List<PortalView> views = new ArrayList<>(SCOPES.size());
            Iterator<Scope> iterator = SCOPES.descendingIterator();
            while (iterator.hasNext()) {
                views.add(iterator.next().view);
            }
            IrisViewPipelinePath path = path(views, dimension);
            PENDING.remove(path);
            int frame = SystemTimeUniforms.COUNTER.getAsInt();
            boolean missing = !CACHE.contains(path);
            if (missing && FAILED.contains(path)) {
                throw new IllegalStateException("The Iris portal pipeline failed to compile for " + path);
            }
            long started = missing ? System.nanoTime() : 0L;
            if (missing) {
                drawMisses++;
            }
            scope.pipeline = CACHE.enter(path, frame);
            if (missing) {
                drawCreations++;
                drawCreationNanos += System.nanoTime() - started;
            }
            scope.path = path;
            scope.pipeline.settings().apply();
        } else if (!scope.path.dimension().equals(text(dimension))) {
            throw new IllegalStateException("A portal pipeline changed dimension while rendering");
        }
        IrisRenderingPipeline rendering = scope.pipeline.rendering();
        ShaderStorageBufferHolder buffers = ((IrisPortalRenderingAccess) rendering).wormholes$storageBuffers();
        if (buffers != null) {
            buffers.setupBuffers();
        }
        return rendering;
    }

    static void beginPreparationPlan(List<IrisViewPipelinePath> paths, int unavailableTravelPaths) {
        RenderSystem.assertOnRenderThread();
        if (unavailableTravelPaths > 0 && capacityMisses == 0) {
            LOGGER.warn("Iris portal preparation reached its {}-camera cache limit; {} future travel paths remain unprepared",
                MAX_VIEWS, unavailableTravelPaths);
        }
        capacityMisses = unavailableTravelPaths;
        PENDING.clear();
        PLANNED.clear();
        if (!Iris.isPackInUseQuick()) {
            return;
        }
        for (IrisViewPipelinePath path : paths) {
            if (PLANNED.size() == MAX_VIEWS) {
                break;
            }
            PLANNED.add(path);
            if (!CACHE.retain(path) && !FAILED.contains(path)) {
                PENDING.add(path);
            }
        }
    }

    static void continueCameras(List<PortalViews.CameraContinuation> continuations, List<PortalView> current,
                                List<IrisViewPipelinePath> planned) {
        if (continuations.isEmpty()) {
            return;
        }
        RenderSystem.assertOnRenderThread();
        if (!SCOPES.isEmpty()) {
            throw new IllegalStateException("Portal camera ownership cannot change while rendering");
        }
        Set<IrisViewPipelinePath.Camera> live = new HashSet<>();
        for (PortalView view : current) {
            live.add(camera(view));
        }
        Map<IrisViewPipelinePath.Camera, IrisViewPipelinePath.Camera> replacements = new HashMap<>();
        for (PortalViews.CameraContinuation continuation : continuations) {
            IrisViewPipelinePath.Camera previous = camera(continuation.previous());
            if (!live.contains(previous)) {
                replacements.put(previous, camera(continuation.next()));
            }
        }
        if (replacements.isEmpty()) {
            return;
        }
        Set<IrisViewPipelinePath> retained = new HashSet<>(planned);
        CACHE.transfer(path -> continuedPath(path, replacements), retained::contains, SystemTimeUniforms.COUNTER.getAsInt());
    }

    static IrisViewPipelinePath continuedPath(IrisViewPipelinePath path,
                                              Map<IrisViewPipelinePath.Camera, IrisViewPipelinePath.Camera> replacements) {
        List<IrisViewPipelinePath.Camera> cameras = new ArrayList<>(path.cameras().size());
        for (IrisViewPipelinePath.Camera camera : path.cameras()) {
            cameras.add(replacements.getOrDefault(camera, camera));
        }
        return new IrisViewPipelinePath(cameras, path.dimension());
    }

    public static boolean preparationStep() {
        RenderSystem.assertOnRenderThread();
        if (!SCOPES.isEmpty()) {
            throw new IllegalStateException("Portal pipelines must be prepared outside portal rendering");
        }
        if (!Iris.isPackInUseQuick()) {
            PENDING.clear();
            return true;
        }
        expire();
        Iterator<IrisViewPipelinePath> iterator = PENDING.iterator();
        if (!iterator.hasNext()) {
            return true;
        }
        IrisViewPipelinePath path = iterator.next();
        iterator.remove();
        long started = System.nanoTime();
        IrisCapturedState captured = IrisCapturedState.capture();
        try (PortalTextureScope textures = new PortalTextureScope();
             PortalFramebufferScope framebuffer = PortalFramebufferScope.capture()) {
            CACHE.prepare(path, SystemTimeUniforms.COUNTER.getAsInt(), PLANNED);
        } catch (RuntimeException failure) {
            LOGGER.error("Unable to prepare the Iris portal pipeline for {}", path, failure);
        } finally {
            captured.restore();
            ProgramUniforms.clearActiveUniforms();
            ProgramSamplers.clearActiveSamplers();
            preparationNanos += System.nanoTime() - started;
        }
        return PENDING.isEmpty();
    }

    public static Diagnostics diagnostics() {
        IrisViewPipelineCache.Statistics statistics = CACHE.statistics();
        return new Diagnostics(statistics.hits(), statistics.creations(), statistics.preparations(), statistics.evictions(),
            statistics.expirations(), destructions, drawMisses, drawCreations, failedCreations, creationNanos, preparationNanos,
            drawCreationNanos, destructionNanos, statistics.size(), PENDING.size(), capacityMisses);
    }

    public static void forget(PortalView view) {
        IrisViewPipelinePath.Camera camera = camera(view);
        PENDING.removeIf(path -> path.cameras().contains(camera));
        PLANNED.removeIf(path -> path.cameras().contains(camera));
    }

    public static void clear() {
        PENDING.clear();
        PLANNED.clear();
        FAILED.clear();
        capacityMisses = 0;
        if (CACHE.size() == 0) {
            return;
        }
        try (PortalTextureScope textures = new PortalTextureScope();
             PortalFramebufferScope framebuffer = PortalFramebufferScope.capture()) {
            CACHE.close();
        }
    }

    private static void expire() {
        long cutoff = System.nanoTime() - MAX_IDLE_NANOS;
        if (!CACHE.hasExpired(cutoff)) {
            return;
        }
        try (PortalTextureScope textures = new PortalTextureScope();
             PortalFramebufferScope framebuffer = PortalFramebufferScope.capture()) {
            CACHE.expire(cutoff);
        }
    }

    private static IrisViewPipelinePath path(List<PortalView> views, NamespacedId dimension) {
        List<IrisViewPipelinePath.Camera> cameras = new ArrayList<>(views.size());
        for (PortalView view : views) {
            cameras.add(camera(view));
        }
        return new IrisViewPipelinePath(cameras, text(dimension));
    }

    static IrisViewPipelinePath.Camera camera(PortalView view) {
        return new IrisViewPipelinePath.Camera(view.kind().name(), view.key(), view.source().dimension().identifier().toString(),
            view.destination().dimension().identifier().toString(), view.toDestination());
    }

    private static String text(NamespacedId dimension) {
        return dimension.getNamespace() + ":" + dimension.getName();
    }

    private static Pipeline create(IrisViewPipelinePath path) {
        ShaderPack pack = Iris.getCurrentPack().orElseThrow(() -> new IllegalStateException("No shader pack is available for a portal view"));
        IrisWorldSettings previous = IrisWorldSettings.capture();
        long started = System.nanoTime();
        try {
            IrisRenderingPipeline rendering = new IrisRenderingPipeline(pack.getProgramSet(new NamespacedId(path.dimension())));
            ((IrisPortalRenderingAccess) rendering).wormholes$initializedBlockIds(previous.blocks() != null);
            return new Pipeline(rendering, IrisWorldSettings.capture());
        } catch (RuntimeException | Error failure) {
            failedCreations++;
            if (FAILED.size() == MAX_VIEWS) {
                FAILED.remove(FAILED.iterator().next());
            }
            FAILED.add(path);
            throw failure;
        } finally {
            creationNanos += System.nanoTime() - started;
            previous.apply();
        }
    }

    private static void destroy(Pipeline pipeline) {
        long started = System.nanoTime();
        try {
            pipeline.rendering().destroy();
            destructions++;
        } finally {
            destructionNanos += System.nanoTime() - started;
        }
    }

    public record Diagnostics(long hits, long creations, long preparations, long evictions, long expirations,
                              long destructions, long drawMisses, long drawCreations, long failedCreations, long creationNanos,
                              long preparationNanos, long drawCreationNanos, long destructionNanos, int cached, int pending,
                              int capacityMisses) {
    }

    private record Pipeline(IrisRenderingPipeline rendering, IrisWorldSettings settings) {
    }

    static final class Scope implements AutoCloseable {
        private final PortalView view;
        private final IrisWorldSettings settings;
        private final IrisCapturedState captured;
        private final PortalTextureScope textures;
        private final PortalFramebufferScope framebuffer;
        private IrisViewPipelinePath path;
        private Pipeline pipeline;

        private Scope(PortalView view) {
            this.view = view;
            settings = IrisWorldSettings.capture();
            captured = IrisCapturedState.capture();
            framebuffer = PortalFramebufferScope.capture();
            textures = new PortalTextureScope();
        }

        @Override
        public void close() {
            if (SCOPES.peek() != this) {
                throw new IllegalStateException("Portal pipeline scope order");
            }
            SCOPES.pop();
            try {
                if (path != null) {
                    CACHE.exit(path);
                }
                captured.restore();
                settings.apply();
                ProgramUniforms.clearActiveUniforms();
                ProgramSamplers.clearActiveSamplers();
            } finally {
                try {
                    textures.close();
                } finally {
                    if (framebuffer != null) {
                        framebuffer.close();
                    }
                }
            }
        }
    }
}
