package art.arcane.wormholes.modded.client.render;

import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.programs.ShaderSupplier;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import org.junit.Test;

import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class PortalIrisShaderLoadingTest {
    private static final EnumSet<ShaderKey> UNUSED = EnumSet.of(
        ShaderKey.HAND_CUTOUT, ShaderKey.HAND_CUTOUT_GLINT, ShaderKey.HAND_CUTOUT_GLINT_DIFFUSE,
        ShaderKey.HAND_CUTOUT_GLINT_SPECIAL, ShaderKey.HAND_CUTOUT_GLINT_ARMOR, ShaderKey.HAND_CUTOUT_BRIGHT,
        ShaderKey.HAND_CUTOUT_DIFFUSE, ShaderKey.HAND_TEXT, ShaderKey.HAND_TEXT_TRANSLUCENT,
        ShaderKey.HAND_TEXT_INTENSITY, ShaderKey.HAND_TRANSLUCENT, ShaderKey.HAND_TRANSLUCENT_GLINT,
        ShaderKey.HAND_TRANSLUCENT_GLINT_DIFFUSE, ShaderKey.HAND_TRANSLUCENT_GLINT_SPECIAL,
        ShaderKey.HAND_TRANSLUCENT_GLINT_ARMOR, ShaderKey.HAND_WATER_BRIGHT, ShaderKey.HAND_WATER_DIFFUSE,
        ShaderKey.SODIUM_TERRAIN_SOLID, ShaderKey.SODIUM_TERRAIN_CUTOUT, ShaderKey.SODIUM_TERRAIN_TRANSLUCENT,
        ShaderKey.SHADOW_SODIUM_TERRAIN_SOLID, ShaderKey.SHADOW_SODIUM_TERRAIN_CUTOUT,
        ShaderKey.SHADOW_SODIUM_TERRAIN_TRANSLUCENT);

    @Test
    public void destinationQueueNeverCreatesUnusedHandOrSodiumTerrainPrograms() {
        AtomicInteger calls = new AtomicInteger();
        PortalIrisShaderLoading loading;
        try (PortalIrisShaderLoading.Scope scope = PortalIrisShaderLoading.constructing()) {
            loading = scope.loading();
            for (ShaderKey key : UNUSED) {
                assertNull(PortalIrisShaderLoading.shader((requested, patch) -> {
                    calls.incrementAndGet();
                    return null;
                }, key, key.patch));
            }
            assertEquals(23, UNUSED.size());
            assertEquals(0, loading.stats().pending());
        }
        try (loading) {
            assertTrue(loading.advance());
            assertEquals(0, calls.get());
            assertEquals(0L, loading.stats().steps());
        }
        assertFalse(PortalIrisShaderLoading.deferred());
    }

    @Test
    public void destinationQueueRetainsEveryOtherShaderIncludingCloudsEntitiesAndTheirShadows() {
        EnumSet<ShaderKey> expected = EnumSet.complementOf(UNUSED);
        EnumSet<ShaderKey> created = EnumSet.noneOf(ShaderKey.class);
        PortalIrisShaderLoading loading;
        try (PortalIrisShaderLoading.Scope scope = PortalIrisShaderLoading.constructing()) {
            loading = scope.loading();
            for (ShaderKey key : ShaderKey.values()) {
                assertNull(PortalIrisShaderLoading.shader((requested, patch) -> {
                    assertSame(requested.patch, patch);
                    assertTrue(created.add(requested));
                    return null;
                }, key, key.patch));
            }
            assertTrue(created.isEmpty());
            loading.attach(pipeline(new AtomicBoolean(true)), mock(ProgramSet.class));
            assertEquals(expected.size() + 1, loading.stats().pending());
        }
        try (loading) {
            while (loading.stats().pending() > 0) {
                loading.advance();
            }
            assertEquals(expected, created);
            assertTrue(created.contains(ShaderKey.CLOUDS_SODIUM));
            assertTrue(created.contains(ShaderKey.ENTITIES_CUTOUT));
            assertTrue(created.contains(ShaderKey.SHADOW_ENTITIES_CUTOUT));
        }
    }

    @Test
    public void destinationWithoutShadowTargetsNeverInvokesShadowShaderFactories() {
        AtomicInteger shadowCalls = new AtomicInteger();
        AtomicInteger terrainCalls = new AtomicInteger();
        int retainedShadows = 0;
        PortalIrisShaderLoading loading;
        try (PortalIrisShaderLoading.Scope scope = PortalIrisShaderLoading.constructing()) {
            loading = scope.loading();
            for (ShaderKey key : ShaderKey.values()) {
                if (key.isShadow() && !UNUSED.contains(key)) {
                    retainedShadows++;
                    PortalIrisShaderLoading.shader((requested, patch) -> {
                        shadowCalls.incrementAndGet();
                        return null;
                    }, key, key.patch);
                }
            }
            PortalIrisShaderLoading.shader((requested, patch) -> {
                terrainCalls.incrementAndGet();
                return null;
            }, ShaderKey.TERRAIN_SOLID, ShaderKey.TERRAIN_SOLID.patch);
            loading.attach(pipeline(new AtomicBoolean(false)), mock(ProgramSet.class));
        }
        try (loading) {
            while (!loading.ready()) {
                loading.advance();
            }
            assertEquals(18, retainedShadows);
            assertEquals(0, shadowCalls.get());
            assertEquals(1, terrainCalls.get());
        }
    }

    @Test
    public void targetsAllocatedByEarlierQueuedProgramsPreserveShadowShaderFactories() {
        AtomicBoolean shadows = new AtomicBoolean(false);
        AtomicInteger calls = new AtomicInteger();
        PortalIrisShaderLoading loading;
        try (PortalIrisShaderLoading.Scope scope = PortalIrisShaderLoading.constructing()) {
            loading = scope.loading();
            PortalIrisShaderLoading.defer(() -> shadows.set(true));
            PortalIrisShaderLoading.shader((requested, patch) -> {
                calls.incrementAndGet();
                return null;
            }, ShaderKey.SHADOW_ENTITIES_CUTOUT, ShaderKey.SHADOW_ENTITIES_CUTOUT.patch);
            loading.attach(pipeline(shadows), mock(ProgramSet.class));
        }
        try (loading) {
            while (!loading.ready()) {
                loading.advance();
            }
            assertEquals(1, calls.get());
        }
    }

    @Test
    public void normalWorldFactoryReceivesAllKeysImmediatelyOutsideDestinationConstruction() {
        ShaderSupplier supplier = mock(ShaderSupplier.class);
        AtomicInteger calls = new AtomicInteger();
        assertFalse(PortalIrisShaderLoading.deferred());
        for (ShaderKey key : ShaderKey.values()) {
            assertSame(supplier, PortalIrisShaderLoading.shader((requested, patch) -> {
                assertSame(key, requested);
                assertSame(key.patch, patch);
                calls.incrementAndGet();
                return supplier;
            }, key, key.patch));
        }
        assertEquals(ShaderKey.values().length, calls.get());
    }

    @Test
    public void preparedNormalWorldRetainsAllHandAndSodiumProgramsWithoutClipping() {
        EnumSet<ShaderKey> created = EnumSet.noneOf(ShaderKey.class);
        PortalIrisShaderLoading loading;
        try (PortalIrisShaderLoading.Scope scope = PortalIrisShaderLoading.main()) {
            loading = scope.loading();
            for (ShaderKey key : ShaderKey.values()) {
                PortalIrisShaderLoading.shader((requested, patch) -> {
                    assertFalse(PortalIrisClipCompilation.active());
                    assertTrue(created.add(requested));
                    return null;
                }, key, key.patch);
            }
            assertTrue(created.isEmpty());
            loading.attach(pipeline(new AtomicBoolean(true)), mock(ProgramSet.class));
        }
        try (loading) {
            while (!loading.ready()) {
                loading.advance();
            }
            assertEquals(EnumSet.allOf(ShaderKey.class), created);
            assertTrue(created.containsAll(UNUSED));
        }
        assertFalse(PortalIrisShaderLoading.deferred());
    }

    private static IrisRenderingPipeline pipeline(AtomicBoolean shadows) {
        IrisRenderingPipeline pipeline = mock(IrisRenderingPipeline.class, withSettings().extraInterfaces(PortalDeferredShaderPipeline.class));
        when(((PortalDeferredShaderPipeline) pipeline).wormholes$hasShadows()).thenAnswer(invocation -> shadows.get());
        return pipeline;
    }
}
