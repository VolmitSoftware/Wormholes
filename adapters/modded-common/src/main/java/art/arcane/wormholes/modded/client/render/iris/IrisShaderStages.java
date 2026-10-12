package art.arcane.wormholes.modded.client.render.iris;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL20C;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

public final class IrisShaderStages {
    private static final int MAX_STAGES = 256;
    private static final long MAX_SOURCE_CHARACTERS = 16_777_216L;
    private static final IrisShaderStageCache CACHE = new IrisShaderStageCache(
        new IrisShaderStageCache.Options(MAX_STAGES, MAX_SOURCE_CHARACTERS, GL20C::glDeleteShader));
    private static final ArrayDeque<Scope> SCOPES = new ArrayDeque<>();
    private static long compilationNanos;

    private IrisShaderStages() {
    }

    public static Scope open() {
        RenderSystem.assertOnRenderThread();
        Scope scope = new Scope();
        SCOPES.push(scope);
        return scope;
    }

    public static int acquire(int type, String source, IntSupplier compile) {
        Scope scope = SCOPES.peek();
        if (scope == null || source == null) {
            return compile.getAsInt();
        }
        int shader = CACHE.acquire(new IrisShaderStageCache.Key(type, source), () -> compile(compile));
        scope.shaders.add(shader);
        return shader;
    }

    public static boolean release(int shader) {
        for (Scope scope : SCOPES) {
            if (scope.shaders.remove(Integer.valueOf(shader))) {
                break;
            }
        }
        return CACHE.release(shader);
    }

    public static void clear() {
        RenderSystem.assertOnRenderThread();
        CACHE.clear();
    }

    public static Diagnostics diagnostics() {
        IrisShaderStageCache.Statistics statistics = CACHE.statistics();
        return new Diagnostics(statistics.hits(), statistics.compilations(), statistics.uncachedCompilations(), statistics.evictions(),
            statistics.releases(), statistics.cached(), statistics.retired(), statistics.leases(), statistics.sourceCharacters(), compilationNanos);
    }

    private static int compile(IntSupplier operation) {
        long started = System.nanoTime();
        try {
            return operation.getAsInt();
        } finally {
            compilationNanos += System.nanoTime() - started;
        }
    }

    public record Diagnostics(long hits, long compilations, long uncachedCompilations, long evictions, long releases,
                              int cached, int retired, int leases, long sourceCharacters, long compilationNanos) {
    }

    public static final class Scope implements AutoCloseable {
        private final List<Integer> shaders = new ArrayList<>(5);

        private Scope() {
        }

        public void linked(int program) {
            for (int shader : shaders) {
                GL20C.glDetachShader(program, shader);
            }
        }

        @Override
        public void close() {
            if (SCOPES.peek() != this) {
                throw new IllegalStateException("Shader stage scope order");
            }
            SCOPES.pop();
            Throwable failure = null;
            for (int shader : shaders) {
                try {
                    if (!CACHE.release(shader)) {
                        GL20C.glDeleteShader(shader);
                    }
                } catch (RuntimeException | Error releaseFailure) {
                    if (failure == null) {
                        failure = releaseFailure;
                    } else {
                        failure.addSuppressed(releaseFailure);
                    }
                }
            }
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }
}
