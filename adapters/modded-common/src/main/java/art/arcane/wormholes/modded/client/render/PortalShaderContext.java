package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.stream.EnvironmentState;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Camera;
import org.joml.Matrix4fc;

import java.util.Objects;

public final class PortalShaderContext implements AutoCloseable {
    private static final ThreadLocal<PortalShaderContext> CURRENT = new ThreadLocal<>();

    private final PortalShaderContext previous;
    private final View view;
    private final RenderTarget target;
    private boolean drawing;

    PortalShaderContext(View view) {
        previous = CURRENT.get();
        this.view = Objects.requireNonNull(view);
        target = view.target();
        CURRENT.set(this);
    }

    PortalShaderContext(RenderTarget target) {
        previous = CURRENT.get();
        view = null;
        this.target = Objects.requireNonNull(target);
        CURRENT.set(this);
    }

    public static RenderTarget target() {
        PortalShaderContext context = CURRENT.get();
        return context == null ? null : context.target;
    }

    public static View current() {
        PortalShaderContext context = CURRENT.get();
        return context == null ? null : context.view;
    }

    public static boolean drawing() {
        PortalShaderContext context = CURRENT.get();
        return context != null && context.drawing;
    }

    void drawing(boolean value) {
        drawing = value;
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    public record View(EnvironmentState environment, Camera camera, RenderTarget target,
                       Matrix4fc modelView, Matrix4fc projection) {
        public View {
            Objects.requireNonNull(environment);
            Objects.requireNonNull(camera);
            Objects.requireNonNull(target);
            Objects.requireNonNull(modelView);
            Objects.requireNonNull(projection);
        }
    }
}
