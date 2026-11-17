package art.arcane.wormholes.modded.client.render;

import com.mojang.logging.LogUtils;
import net.irisshaders.iris.api.v0.IrisApi;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Keeps Wormholes' own draws out of Iris's immediate-mode vertex extension. Iris 1.7 and Oculus 1.8 expose only
 * {@code isRenderingLevel}, {@code renderWithExtendedVertexFormat} and {@code usingTessellation} on
 * {@code ImmediateState}; the 26.x bypass, skip-extension and framebuffer-routing switches do not exist there.
 */
public final class PortalShaderScope implements AutoCloseable {
    private static final Bindings IRIS = findBindings();
    private static final PortalShaderScope NONE = new PortalShaderScope(null, false);
    private static final ThreadLocal<Integer> RENDER_DEPTH = ThreadLocal.withInitial(() -> 0);

    private final Bindings bindings;
    private final boolean rendering;
    private final boolean previousRenderingLevel;
    private final boolean previousExtendedFormat;
    private final boolean previousTessellation;

    private PortalShaderScope(Bindings bindings, boolean rendering) {
        this.bindings = bindings;
        this.rendering = rendering;
        boolean active = bindings != null && rendering;
        previousRenderingLevel = active && (boolean) bindings.renderingLevel().get();
        previousExtendedFormat = active && (boolean) bindings.extendedFormat().get();
        previousTessellation = active && (boolean) bindings.tessellation().get();
        if (active) {
            bindings.renderingLevel().set(false);
            bindings.extendedFormat().set(false);
            bindings.tessellation().set(false);
        }
        if (rendering) {
            RENDER_DEPTH.set(RENDER_DEPTH.get() + 1);
        }
    }

    static PortalShaderScope vertices() {
        return IRIS == null ? NONE : new PortalShaderScope(IRIS, false);
    }

    public static PortalShaderScope rendering() {
        return new PortalShaderScope(IRIS, true);
    }

    public static boolean isRendering() {
        return RENDER_DEPTH.get() > 0;
    }

    public static boolean shadowPass() {
        return IRIS != null && IrisApi.getInstance().isRenderingShadowPass();
    }

    public static boolean shaders() {
        return IRIS != null && IrisApi.getInstance().isShaderPackInUse();
    }

    static boolean irisPresent() {
        return IRIS != null;
    }

    @Override
    public void close() {
        if (bindings != null && rendering) {
            bindings.renderingLevel().set(previousRenderingLevel);
            bindings.extendedFormat().set(previousExtendedFormat);
            bindings.tessellation().set(previousTessellation);
        }
        if (rendering) {
            int depth = RENDER_DEPTH.get() - 1;
            if (depth <= 0) {
                RENDER_DEPTH.remove();
            } else {
                RENDER_DEPTH.set(depth);
            }
        }
    }

    private static Bindings findBindings() {
        Class<?> state;
        try {
            state = Class.forName("net.irisshaders.iris.vertices.ImmediateState", false, PortalShaderScope.class.getClassLoader());
        } catch (ClassNotFoundException absent) {
            return null;
        }
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            return new Bindings(lookup.findStaticVarHandle(state, "isRenderingLevel", boolean.class),
                lookup.findStaticVarHandle(state, "renderWithExtendedVertexFormat", boolean.class),
                lookup.findStaticVarHandle(state, "usingTessellation", boolean.class));
        } catch (NoSuchFieldException | IllegalAccessException | LinkageError | RuntimeException failure) {
            LogUtils.getLogger().warn("Portal draws are not isolated from Iris immediate state", failure);
            return null;
        }
    }

    private record Bindings(VarHandle renderingLevel, VarHandle extendedFormat, VarHandle tessellation) {
    }
}
