package art.arcane.wormholes.modded.client.render;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

final class PortalShaderScope implements AutoCloseable {
    private static final BindingResult IRIS = findBindings();
    private static final PortalShaderScope NONE = new PortalShaderScope(null, false);

    private final Bindings bindings;
    private final Boolean previousExtension;
    private final boolean rendering;
    private final boolean previousBypass;
    private final boolean previousRenderingLevel;
    private final boolean previousExtendedFormat;

    PortalShaderScope(Bindings bindings, boolean rendering) {
        this.bindings = bindings;
        this.rendering = rendering;
        previousExtension = bindings == null ? null : bindings.extension().get();
        previousBypass = bindings != null && rendering && (boolean) bindings.bypass().get();
        previousRenderingLevel = bindings != null && rendering && (boolean) bindings.renderingLevel().get();
        previousExtendedFormat = bindings != null && rendering && (boolean) bindings.extendedFormat().get();
        if (bindings != null) {
            bindings.extension().set(true);
            if (rendering) {
                bindings.bypass().set(true);
                bindings.renderingLevel().set(false);
                bindings.extendedFormat().set(false);
            }
        }
    }

    static PortalShaderScope vertices() {
        return IRIS.open(false);
    }

    static PortalShaderScope rendering() {
        return IRIS.open(true);
    }

    @Override
    public void close() {
        if (bindings != null) {
            try {
                if (rendering) {
                    bindings.bypass().set(previousBypass);
                    bindings.renderingLevel().set(previousRenderingLevel);
                    bindings.extendedFormat().set(previousExtendedFormat);
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
                lookup.findStaticVarHandle(state, "renderWithExtendedVertexFormat", boolean.class));
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

    record Bindings(ThreadLocal<Boolean> extension, VarHandle bypass, VarHandle renderingLevel, VarHandle extendedFormat) {
    }
}
