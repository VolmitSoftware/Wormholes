package art.arcane.wormholes.modded.client.render;

import org.junit.Before;
import org.junit.Test;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PortalShaderScopeTest {
    private static boolean bypass;
    private static boolean renderingLevel;
    private static boolean extendedFormat;
    private final ThreadLocal<Boolean> extension = ThreadLocal.withInitial(() -> false);
    private PortalShaderScope.Bindings bindings;

    @Before
    public void setUp() throws ReflectiveOperationException {
        bypass = false;
        renderingLevel = true;
        extendedFormat = true;
        bindings = new PortalShaderScope.Bindings(extension,
            MethodHandles.lookup().findStaticVarHandle(PortalShaderScopeTest.class, "bypass", boolean.class),
            MethodHandles.lookup().findStaticVarHandle(PortalShaderScopeTest.class, "renderingLevel", boolean.class),
            MethodHandles.lookup().findStaticVarHandle(PortalShaderScopeTest.class, "extendedFormat", boolean.class));
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
    public void meshingRestoresTheCallingThreadsVertexFormatFlag() {
        try (PortalShaderScope scope = new PortalShaderScope(bindings, false)) {
            assertTrue(extension.get());
            assertFalse(bypass);
            assertTrue(renderingLevel);
            assertTrue(extendedFormat);
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

    public static final class MissingIrisFields {
    }

    public static final class BrokenIrisInitializer {
        public static final ThreadLocal<Boolean> skipExtension = initialize();

        private static ThreadLocal<Boolean> initialize() {
            throw new IllegalStateException("Shader state initialization failed");
        }
    }
}
