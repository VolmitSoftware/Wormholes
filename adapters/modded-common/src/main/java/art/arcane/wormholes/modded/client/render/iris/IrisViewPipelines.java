package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.wormholes.modded.client.render.PortalFramebufferScope;
import art.arcane.wormholes.modded.client.render.PortalTextureScope;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.modded.mixin.client.IrisPortalRenderingAccess;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class IrisViewPipelines {
    private static final int MAX_VIEWS = 12;
    private static final long MAX_IDLE_NANOS = 60_000_000_000L;
    private static final ArrayDeque<Scope> SCOPES = new ArrayDeque<>();
    private static final IrisViewPipelineCache<Path, Pipeline> CACHE = new IrisViewPipelineCache<>(
        new IrisViewPipelineCache.Options<>(MAX_VIEWS, IrisViewPipelines::create, pipeline -> pipeline.rendering().destroy()));

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
            long cutoff = System.nanoTime() - MAX_IDLE_NANOS;
            if (CACHE.hasExpired(cutoff)) {
                try (PortalTextureScope textures = new PortalTextureScope();
                     PortalFramebufferScope framebuffer = PortalFramebufferScope.capture()) {
                    CACHE.expire(cutoff);
                }
            }
            return null;
        }
        if (scope.path == null) {
            List<PortalView> views = new ArrayList<>(SCOPES.size());
            Iterator<Scope> iterator = SCOPES.descendingIterator();
            while (iterator.hasNext()) {
                views.add(iterator.next().view);
            }
            Path path = new Path(List.copyOf(views), dimension);
            int frame = SystemTimeUniforms.COUNTER.getAsInt();
            CACHE.expire(System.nanoTime() - MAX_IDLE_NANOS);
            scope.pipeline = CACHE.enter(path, frame);
            scope.path = path;
            scope.pipeline.settings().apply();
        } else if (!scope.path.dimension().equals(dimension)) {
            throw new IllegalStateException("A portal pipeline changed dimension while rendering");
        }
        IrisRenderingPipeline rendering = scope.pipeline.rendering();
        ShaderStorageBufferHolder buffers = ((IrisPortalRenderingAccess) rendering).wormholes$storageBuffers();
        if (buffers != null) {
            buffers.setupBuffers();
        }
        return rendering;
    }

    public static void forget(PortalView view) {
        if (CACHE.size() == 0) {
            return;
        }
        try (PortalTextureScope textures = new PortalTextureScope();
             PortalFramebufferScope framebuffer = PortalFramebufferScope.capture()) {
            CACHE.removeIf(path -> path.views().contains(view));
        }
    }

    public static void clear() {
        if (CACHE.size() == 0) {
            return;
        }
        try (PortalTextureScope textures = new PortalTextureScope();
             PortalFramebufferScope framebuffer = PortalFramebufferScope.capture()) {
            CACHE.close();
        }
    }

    private static Pipeline create(Path path) {
        ShaderPack pack = Iris.getCurrentPack().orElseThrow(() -> new IllegalStateException("No shader pack is available for a portal view"));
        IrisWorldSettings previous = IrisWorldSettings.capture();
        try {
            IrisRenderingPipeline rendering = new IrisRenderingPipeline(pack.getProgramSet(path.dimension()));
            ((IrisPortalRenderingAccess) rendering).wormholes$initializedBlockIds(previous.blocks() != null);
            return new Pipeline(rendering, IrisWorldSettings.capture());
        } finally {
            previous.apply();
        }
    }

    private record Path(List<PortalView> views, NamespacedId dimension) {
    }

    private record Pipeline(IrisRenderingPipeline rendering, IrisWorldSettings settings) {
    }

    static final class Scope implements AutoCloseable {
        private final PortalView view;
        private final IrisWorldSettings settings;
        private final IrisCapturedState captured;
        private final PortalTextureScope textures;
        private final PortalFramebufferScope framebuffer;
        private Path path;
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
