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
import java.util.Objects;

public final class PortalIrisShaderLoading implements AutoCloseable {
    private static PortalIrisShaderLoading current;

    private final PortalShaderLinkQueue queue = new PortalShaderLinkQueue(System::nanoTime);
    private final boolean main;
    private IrisRenderingPipeline pipeline;

    private PortalIrisShaderLoading(boolean main) {
        this.main = main;
    }

    static Scope constructing() {
        return new Scope(new PortalIrisShaderLoading(false));
    }

    static Scope main() {
        return new Scope(new PortalIrisShaderLoading(true));
    }

    public static boolean deferred() {
        return current != null;
    }

    public static void defer(Runnable task) {
        Objects.requireNonNull(current, "Destination shader construction scope").queue.add(Objects.requireNonNull(task));
    }

    public static ShaderSupplier shader(BiFunction<ShaderKey, Patch, ShaderSupplier> factory, ShaderKey key, Patch patch) {
        if (current == null) {
            return factory.apply(key, patch);
        }
        if (!current.main && !destinationShader(key)) {
            return null;
        }
        PortalIrisShaderLoading owner = current;
        owner.queue.add(() -> owner.create(factory, key, patch));
        return null;
    }

    void attach(IrisRenderingPipeline pipeline, ProgramSet programs) {
        this.pipeline = pipeline;
        queue.add(() -> finish(programs));
    }

    boolean advance(long budgetNanos) {
        return queue.advance(budgetNanos);
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

    private static boolean destinationShader(ShaderKey key) {
        return switch (key) {
            case HAND_CUTOUT, HAND_CUTOUT_GLINT, HAND_CUTOUT_GLINT_DIFFUSE, HAND_CUTOUT_GLINT_SPECIAL,
                 HAND_CUTOUT_GLINT_ARMOR, HAND_CUTOUT_BRIGHT, HAND_CUTOUT_DIFFUSE, HAND_TEXT,
                 HAND_TEXT_TRANSLUCENT, HAND_TEXT_INTENSITY, HAND_TRANSLUCENT, HAND_TRANSLUCENT_GLINT,
                 HAND_TRANSLUCENT_GLINT_DIFFUSE, HAND_TRANSLUCENT_GLINT_SPECIAL, HAND_TRANSLUCENT_GLINT_ARMOR,
                 HAND_WATER_BRIGHT, HAND_WATER_DIFFUSE, SODIUM_TERRAIN_SOLID, SODIUM_TERRAIN_CUTOUT,
                 SODIUM_TERRAIN_TRANSLUCENT, SHADOW_SODIUM_TERRAIN_SOLID, SHADOW_SODIUM_TERRAIN_CUTOUT,
                 SHADOW_SODIUM_TERRAIN_TRANSLUCENT -> false;
            default -> true;
        };
    }

    private void finish(ProgramSet programs) {
        PortalDeferredShaderPipeline deferred = (PortalDeferredShaderPipeline) pipeline;
        try (Scope scope = new Scope(this)) {
            deferred.wormholes$finishShaders(programs);
        }
        queue.add(deferred::wormholes$completeShaders);
    }

    private void create(BiFunction<ShaderKey, Patch, ShaderSupplier> factory, ShaderKey key, Patch patch) {
        if (key.isShadow() && !((PortalDeferredShaderPipeline) pipeline).wormholes$hasShadows()) {
            return;
        }
        try (PortalIrisClipCompilation clipping = main ? null : PortalIrisClipCompilation.open(key)) {
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
            GlProgram linked = shader.shader().get();
            if (clipping != null && clipping.distance() >= 0) {
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
