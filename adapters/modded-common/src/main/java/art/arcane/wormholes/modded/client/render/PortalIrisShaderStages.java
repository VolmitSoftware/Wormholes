package art.arcane.wormholes.modded.client.render;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.ShaderPack;
import org.lwjgl.sdl.SDLVideo;

import java.util.function.IntSupplier;

public final class PortalIrisShaderStages {
    private static final PortalShaderStageCache CACHE = new PortalShaderStageCache(
        new PortalShaderStageCache.Limits(512, 32L * 1024 * 1024), GlStateManager::glDeleteShader);
    private static int destinationDepth;

    private PortalIrisShaderStages() {
    }

    public static Scope destination() {
        RenderSystem.assertOnRenderThread();
        destinationDepth++;
        return new Scope(destinationDepth, CACHE.scope());
    }

    public static int compile(int type, String source, IntSupplier compiler) {
        RenderSystem.assertOnRenderThread();
        long context = SDLVideo.SDL_GL_GetCurrentContext();
        ShaderPack pack = Iris.getCurrentPack().orElse(null);
        if (context == 0 || pack == null || source == null) {
            return compiler.getAsInt();
        }
        return CACHE.compile(new PortalShaderStageCache.Request(context, pack, type, source, destinationDepth > 0), compiler);
    }

    public static void delete(int handle) {
        RenderSystem.assertOnRenderThread();
        if (!CACHE.release(SDLVideo.SDL_GL_GetCurrentContext(), handle)) {
            GlStateManager.glDeleteShader(handle);
        }
    }

    public static void clearCurrentContext() {
        RenderSystem.assertOnRenderThread();
        CACHE.clear(SDLVideo.SDL_GL_GetCurrentContext());
    }

    public static void discardOwned(int handle) {
        RenderSystem.assertOnRenderThread();
        CACHE.release(SDLVideo.SDL_GL_GetCurrentContext(), handle);
    }

    public static void linkTime(long nanos) {
        CACHE.linkTime(nanos);
    }

    public static Stats stats() {
        PortalShaderStageCache.Stats stats = CACHE.stats();
        return new Stats(stats.hits(), stats.misses(), stats.compileNanos(), stats.linkNanos(), stats.residentStages(),
            stats.borrowedStages(), stats.sourceBytes());
    }

    public record Stats(long hits, long misses, long compileNanos, long linkNanos, int residentStages, int borrowedStages, long sourceBytes) {
    }

    public static final class Scope implements AutoCloseable {
        private final int depth;
        private final PortalShaderStageCache.Scope borrows;
        private boolean closed;

        private Scope(int depth, PortalShaderStageCache.Scope borrows) {
            this.depth = depth;
            this.borrows = borrows;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (destinationDepth != depth) {
                throw new IllegalStateException("shader stage scope order");
            }
            closed = true;
            try {
                borrows.close();
            } finally {
                destinationDepth--;
            }
        }
    }
}
