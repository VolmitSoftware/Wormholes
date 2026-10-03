package art.arcane.wormholes.modded.client.render;

import com.mojang.renderpearl.backend.opengl.GlProgram;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import net.irisshaders.iris.gl.shader.ShaderCompileException;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.programs.ShaderSupplier;
import net.irisshaders.iris.pipeline.transform.Patch;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import org.lwjgl.opengl.GL20C;

import java.util.function.BiFunction;

public final class PortalIrisShaderLoading implements AutoCloseable {
    private static final long FRAME_BUDGET_NANOS = 4_000_000L;
    private static PortalIrisShaderLoading current;

    private final PortalShaderLinkQueue queue = new PortalShaderLinkQueue(System::nanoTime);
    private IrisRenderingPipeline pipeline;

    private PortalIrisShaderLoading() {
    }

    static Scope constructing() {
        return new Scope(new PortalIrisShaderLoading());
    }

    public static boolean deferred() {
        return current != null;
    }

    public static ShaderSupplier shader(BiFunction<ShaderKey, Patch, ShaderSupplier> factory, ShaderKey key, Patch patch) {
        if (current == null) {
            return factory.apply(key, patch);
        }
        PortalIrisShaderLoading owner = current;
        owner.queue.add(() -> owner.create(factory, key, patch));
        return null;
    }

    void attach(IrisRenderingPipeline pipeline, ProgramSet programs) {
        this.pipeline = pipeline;
        queue.add(() -> ((PortalDeferredShaderPipeline) pipeline).wormholes$finishShaders(programs));
    }

    boolean advance() {
        return queue.advance(FRAME_BUDGET_NANOS);
    }

    boolean ready() {
        return pipeline != null && queue.ready();
    }

    PortalShaderLinkQueue.Stats stats() {
        return queue.stats();
    }

    @Override
    public void close() {
        queue.close();
    }

    private void create(BiFunction<ShaderKey, Patch, ShaderSupplier> factory, ShaderKey key, Patch patch) {
        try (PortalIrisClipCompilation clipping = PortalIrisClipCompilation.open(key)) {
            install(factory.apply(key, patch), clipping);
        }
    }

    private void install(ShaderSupplier shader, PortalIrisClipCompilation clipping) {
        if (shader == null) {
            return;
        }
        boolean installed = false;
        try {
            int program = shader.id().program();
            if (GlStateManager.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL20C.GL_FALSE) {
                throw new ShaderCompileException(shader.key().name(), GlStateManager.glGetProgramInfoLog(program, 32768));
            }
            if (shader.key().isShadow()
                && !((PortalDeferredShaderPipeline) pipeline).wormholes$hasShadows()) {
                shader.id().getFinally();
                return;
            }
            GlProgram linked = shader.shader().get();
            if (clipping.distance() >= 0) {
                ((PortalClippedProgram) linked).wormholes$clipping(program, clipping.distance(), clipping.existingDistances());
            }
            ((PortalDeferredShaderPipeline) pipeline).wormholes$shader(shader.key(), linked);
            installed = true;
        } finally {
            if (!installed) {
                ((PortalShaderStageDiscard) (Object) shader.id()).wormholes$discardStages();
                GlStateManager.glDeleteProgram(shader.id().program());
            }
        }
    }

    static final class Scope implements AutoCloseable {
        private final PortalIrisShaderLoading previous;
        private final PortalIrisShaderLoading loading;
        private boolean closed;

        private Scope(PortalIrisShaderLoading loading) {
            previous = current;
            this.loading = loading;
            current = loading;
        }

        PortalIrisShaderLoading loading() {
            return loading;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (current != loading) {
                throw new IllegalStateException("Destination shader construction scope order");
            }
            closed = true;
            current = previous;
        }
    }
}
