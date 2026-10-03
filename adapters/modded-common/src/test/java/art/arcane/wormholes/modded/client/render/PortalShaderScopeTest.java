package art.arcane.wormholes.modded.client.render;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.junit.Before;
import org.junit.Test;

import java.lang.invoke.MethodHandles;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PortalShaderScopeTest {
    private static boolean bypass;
    private static boolean renderingLevel;
    private static boolean extendedFormat;
    private static boolean tessellation;
    private static boolean framebufferRouting;
    private final ThreadLocal<Boolean> extension = ThreadLocal.withInitial(() -> false);
    private PortalShaderScope.Bindings bindings;

    @Before
    public void setUp() throws ReflectiveOperationException {
        bypass = false;
        renderingLevel = true;
        extendedFormat = true;
        tessellation = true;
        framebufferRouting = true;
        bindings = new PortalShaderScope.Bindings(extension,
            MethodHandles.lookup().findStaticVarHandle(PortalShaderScopeTest.class, "bypass", boolean.class),
            MethodHandles.lookup().findStaticVarHandle(PortalShaderScopeTest.class, "renderingLevel", boolean.class),
            MethodHandles.lookup().findStaticVarHandle(PortalShaderScopeTest.class, "extendedFormat", boolean.class),
            MethodHandles.lookup().findStaticVarHandle(PortalShaderScopeTest.class, "tessellation", boolean.class),
            MethodHandles.lookup().findStaticVarHandle(PortalShaderScopeTest.class, "framebufferRouting", boolean.class));
    }

    @Test
    public void featureBufferFormatIsIsolatedFromLevelRendering() {
        try (PortalShaderScope scope = new PortalShaderScope(bindings, true)) {
            assertFalse(renderingLevel);
            assertFalse(extendedFormat);
            extendedFormat = true;
        }
        assertTrue(renderingLevel);
        assertTrue(extendedFormat);
        assertFalse(extension.get());
    }

    @Test
    public void sourceFramebufferRoutingAndTessellationSurviveNestedPortalRendering() {
        assertFalse(PortalShaderScope.isRendering());
        try (PortalShaderScope outer = new PortalShaderScope(bindings, true)) {
            assertTrue(PortalShaderScope.isRendering());
            assertFalse(tessellation);
            assertFalse(framebufferRouting);
            try (PortalShaderScope inner = new PortalShaderScope(bindings, true)) {
                tessellation = true;
                framebufferRouting = true;
            }
            assertFalse(tessellation);
            assertFalse(framebufferRouting);
            assertTrue(PortalShaderScope.isRendering());
        }
        assertTrue(tessellation);
        assertTrue(framebufferRouting);
        assertFalse(PortalShaderScope.isRendering());
    }

    @Test
    public void meshingRestoresTheCallingThreadsVertexFormatFlag() {
        try (PortalShaderScope scope = new PortalShaderScope(bindings, false)) {
            assertTrue(extension.get());
            assertFalse(bypass);
            assertTrue(renderingLevel);
            assertTrue(extendedFormat);
            assertTrue(tessellation);
            assertTrue(framebufferRouting);
            assertFalse(PortalShaderScope.isRendering());
        }
        assertFalse(extension.get());
        assertFalse(bypass);
    }

    @Test
    public void renderingRestoresPreviousStateAfterNestedFailure() {
        try (PortalShaderScope outer = new PortalShaderScope(bindings, true)) {
            assertTrue(extension.get());
            assertTrue(bypass);
            try {
                try (PortalShaderScope inner = new PortalShaderScope(bindings, true)) {
                    throw new IllegalStateException("render failed");
                }
            } catch (IllegalStateException expected) {
                assertTrue(extension.get());
                assertTrue(bypass);
                assertFalse(renderingLevel);
                assertFalse(extendedFormat);
            }
        }
        assertFalse(extension.get());
        assertFalse(bypass);
        assertTrue(renderingLevel);
        assertTrue(extendedFormat);
    }

    @Test
    public void workerMeshingPreservesTheRenderThreadsGlobalOverride() {
        try (PortalShaderScope render = new PortalShaderScope(bindings, true)) {
            CompletableFuture.runAsync(() -> {
                assertFalse(extension.get());
                try (PortalShaderScope mesh = new PortalShaderScope(bindings, false)) {
                    assertTrue(extension.get());
                    assertTrue(bypass);
                    assertFalse(renderingLevel);
                    assertFalse(extendedFormat);
                }
                assertFalse(extension.get());
            }).join();
            assertTrue(extension.get());
            assertTrue(bypass);
        }
        assertFalse(bypass);
    }

    @Test
    public void absentIrisLeavesGlobalStateUntouched() {
        PortalShaderScope.BindingResult absent = new PortalShaderScope.BindingResult(null, null);
        try (PortalShaderScope scope = absent.open(true)) {
            assertFalse(bypass);
            assertTrue(renderingLevel);
            assertTrue(extendedFormat);
            assertFalse(extension.get());
        }
    }

    @Test
    public void incompatibleIrisCanRepeatedlyRefuseRenderingWithoutPoisoningClassInitialization() {
        PortalShaderScope.BindingResult incompatible = PortalShaderScope.resolveBindings(MissingIrisFields.class);
        for (boolean rendering : new boolean[]{false, true, false, true}) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> incompatible.open(rendering));
            assertTrue(failure.getCause() instanceof NoSuchFieldException);
            assertSame(incompatible.failure(), failure.getCause());
        }
        try (PortalShaderScope scope = new PortalShaderScope(bindings, true)) {
            assertTrue(bypass);
        }
        assertFalse(bypass);
        assertTrue(renderingLevel);
    }

    @Test
    public void irisInitializerFailureBecomesRecoverableRefusal() {
        PortalShaderScope.BindingResult incompatible = PortalShaderScope.resolveBindings(BrokenIrisInitializer.class);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> incompatible.open(true));
        assertTrue(failure.getCause() instanceof ExceptionInInitializerError);
        assertThrows(IllegalStateException.class, () -> incompatible.open(false));
        assertFalse(bypass);
        assertTrue(renderingLevel);
    }

    @Test
    public void actualMissingIrisClassesDoNotPreventNativeRendererScopeInitialization() throws Exception {
        ClassLoader isolated = new AbsentIrisLoader(getClass().getClassLoader());
        Class<?> scope = Class.forName(AbsentIrisLoader.ISOLATED, true, isolated);
        for (String name : new String[] {"shaders", "shadowPass", "irisPresent"}) {
            Method method = scope.getDeclaredMethod(name);
            method.setAccessible(true);
            assertFalse((Boolean) method.invoke(null));
        }
        Method rendering = scope.getDeclaredMethod("rendering");
        rendering.setAccessible(true);
        try (AutoCloseable opened = (AutoCloseable) rendering.invoke(null)) {
            assertFalse((Boolean) scope.getDeclaredMethod("isRendering").invoke(null));
        }
    }

    private static final class AbsentIrisLoader extends ClassLoader {
        private static final String ORIGINAL = PortalShaderScope.class.getName();
        private static final String ISOLATED = "art.arcane.wormholes.testing.absent.PortalShaderScope";

        private AbsentIrisLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("net.irisshaders.")) {
                throw new ClassNotFoundException(name);
            }
            if (!name.startsWith(ISOLATED)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    String original = ORIGINAL + name.substring(ISOLATED.length());
                    try (InputStream bytes = getParent().getResourceAsStream(original.replace('.', '/') + ".class")) {
                        if (bytes == null) {
                            throw new ClassNotFoundException(name);
                        }
                        ClassReader reader = new ClassReader(bytes.readAllBytes());
                        ClassWriter writer = new ClassWriter(0);
                        reader.accept(new ClassRemapper(writer, new ScopeRemapper()), 0);
                        byte[] data = writer.toByteArray();
                        loaded = defineClass(name, data, 0, data.length);
                    } catch (IOException failure) {
                        throw new ClassNotFoundException(name, failure);
                    }
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }

    private static final class ScopeRemapper extends Remapper {
        private ScopeRemapper() {
            super(Opcodes.ASM9);
        }

        @Override
        public String map(String internalName) {
            String original = AbsentIrisLoader.ORIGINAL.replace('.', '/');
            return internalName.startsWith(original)
                ? AbsentIrisLoader.ISOLATED.replace('.', '/') + internalName.substring(original.length()) : internalName;
        }
    }

    public static final class MissingIrisFields {
    }

    public static final class BrokenIrisInitializer {
        public static final ThreadLocal<Boolean> skipExtension = initialize();

        private static ThreadLocal<Boolean> initialize() {
            throw new IllegalStateException("Shader state initialization failed");
        }
    }
}
