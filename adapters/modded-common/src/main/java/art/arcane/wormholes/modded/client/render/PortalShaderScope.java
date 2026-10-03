package art.arcane.wormholes.modded.client.render;

import net.irisshaders.iris.api.v0.IrisApi;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public final class PortalShaderScope implements AutoCloseable {
    private static final BindingResult IRIS = findBindings();
    private static final PortalShaderScope NONE = new PortalShaderScope(null, false);
    private static final ThreadLocal<Integer> RENDER_DEPTH = ThreadLocal.withInitial(() -> 0);

    private final Bindings bindings;
    private final Boolean previousExtension;
    private final boolean rendering;
    private final boolean previousBypass;
    private final boolean previousRenderingLevel;
    private final boolean previousExtendedFormat;
    private final boolean previousTessellation;
    private final boolean previousFramebufferRouting;

    PortalShaderScope(Bindings bindings, boolean rendering) {
        this.bindings = bindings;
        this.rendering = rendering;
        previousExtension = bindings == null ? null : bindings.extension().get();
        previousBypass = bindings != null && rendering && (boolean) bindings.bypass().get();
        previousRenderingLevel = bindings != null && rendering && (boolean) bindings.renderingLevel().get();
        previousExtendedFormat = bindings != null && rendering && (boolean) bindings.extendedFormat().get();
        previousTessellation = bindings != null && rendering && (boolean) bindings.tessellation().get();
        previousFramebufferRouting = bindings != null && rendering && (boolean) bindings.framebufferRouting().get();
        if (bindings != null) {
            bindings.extension().set(true);
            if (rendering) {
                bindings.bypass().set(true);
                bindings.renderingLevel().set(false);
                bindings.extendedFormat().set(false);
                bindings.tessellation().set(false);
                bindings.framebufferRouting().set(false);
                RENDER_DEPTH.set(RENDER_DEPTH.get() + 1);
            }
        }
    }

    static PortalShaderScope vertices() {
        return IRIS.open(false);
    }

    static PortalShaderScope rendering() {
        return IRIS.open(true);
    }

    public static boolean isRendering() {
        return RENDER_DEPTH.get() > 0;
    }

    static boolean shadowPass() {
        return IRIS.bindings() != null && IrisApi.getInstance().isRenderingShadowPass();
    }

    static boolean shaders() {
        return IRIS.bindings() != null && IrisApi.getInstance().isShaderPackInUse();
    }

    static boolean irisPresent() {
        return IRIS.bindings() != null;
    }

    @Override
    public void close() {
        if (bindings != null) {
            try {
                if (rendering) {
                    bindings.bypass().set(previousBypass);
                    bindings.renderingLevel().set(previousRenderingLevel);
                    bindings.extendedFormat().set(previousExtendedFormat);
                    bindings.tessellation().set(previousTessellation);
                    bindings.framebufferRouting().set(previousFramebufferRouting);
                    int depth = RENDER_DEPTH.get() - 1;
                    if (depth == 0) {
                        RENDER_DEPTH.remove();
                    } else {
                        RENDER_DEPTH.set(depth);
                    }
                }
            } finally {
                bindings.extension().set(previousExtension);
            }
        }
    }

    private static BindingResult findBindings() {
        try {
            Class<?> state = Class.forName("net.irisshaders.iris.vertices.ImmediateState", false, PortalShaderScope.class.getClassLoader());
            return resolveBindings(state);
        } catch (ClassNotFoundException absent) {
            return new BindingResult(null, null);
        } catch (LinkageError | RuntimeException failure) {
            return new BindingResult(null, failure);
        }
    }

    @SuppressWarnings("unchecked")
    static BindingResult resolveBindings(Class<?> state) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            ThreadLocal<Boolean> extension = (ThreadLocal<Boolean>) lookup.findStaticVarHandle(state, "skipExtension", ThreadLocal.class).get();
            Bindings bindings = new Bindings(extension, lookup.findStaticVarHandle(state, "bypass", boolean.class),
                lookup.findStaticVarHandle(state, "isRenderingLevel", boolean.class),
                lookup.findStaticVarHandle(state, "renderWithExtendedVertexFormat", boolean.class),
                lookup.findStaticVarHandle(state, "usingTessellation", boolean.class),
                lookup.findStaticVarHandle(state, "safeToMultiply", boolean.class));
            return new BindingResult(bindings, null);
        } catch (NoSuchFieldException | IllegalAccessException | LinkageError | RuntimeException failure) {
            return new BindingResult(null, failure);
        }
    }

    record BindingResult(Bindings bindings, Throwable failure) {
        PortalShaderScope open(boolean rendering) {
            if (failure != null) {
                throw new IllegalStateException("Unable to isolate portal rendering from Iris shader state", failure);
            }
            return bindings == null ? NONE : new PortalShaderScope(bindings, rendering);
        }
    }

    record Bindings(ThreadLocal<Boolean> extension, VarHandle bypass, VarHandle renderingLevel, VarHandle extendedFormat,
                    VarHandle tessellation, VarHandle framebufferRouting) {
    }
}
