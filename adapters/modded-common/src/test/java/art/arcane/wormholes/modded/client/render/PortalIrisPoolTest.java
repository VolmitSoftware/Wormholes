package art.arcane.wormholes.modded.client.render;

import org.junit.Test;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import java.lang.reflect.Constructor;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.anyInt;

public class PortalIrisPoolTest {
    private static final long MIB = 1024L * 1024L;

    @Test
    public void reservationEvictsOlderViewsWhilePreservingThisFramesHistoryAndActiveCapture() throws ReflectiveOperationException {
        PortalIrisRenderer pool = new PortalIrisRenderer(() -> mock(ShaderPack.class));
        pool.beginFrame();
        Map<Integer, Object> entries = entries(pool);
        Object active = entry(400 * MIB, 0, 1);
        Object current = entry(400 * MIB, 1, 0);
        Object older = entry(200 * MIB, 0, 0);
        entries.put(1, active);
        entries.put(2, current);
        entries.put(3, older);
        field(PortalIrisRenderer.class, "reserved").setLong(pool, 1000 * MIB);
        reserve(pool, 100 * MIB);
        assertEquals(800 * MIB, pool.bytes());
        assertTrue(entries.containsKey(1));
        assertTrue(entries.containsKey(2));
        assertFalse(entries.containsKey(3));
        closeMethod(older).invoke(verify(older));
        closeMethod(active).invoke(verify(active, never()));
        closeMethod(current).invoke(verify(current, never()));
    }

    @Test
    public void resourceLimitNeverReclaimsAnActiveViewToSatisfyAnotherAllocation() throws ReflectiveOperationException {
        PortalIrisRenderer pool = new PortalIrisRenderer(() -> mock(ShaderPack.class));
        Object active = entry(1000 * MIB, 0, 1);
        entries(pool).put(1, active);
        field(PortalIrisRenderer.class, "reserved").setLong(pool, 1000 * MIB);
        InvocationTargetException failure = assertThrows(InvocationTargetException.class, () -> reserve(pool, 100 * MIB));
        assertTrue(failure.getCause() instanceof IllegalStateException);
        assertSame(active, entries(pool).get(1));
        assertEquals(1000 * MIB, pool.bytes());
        closeMethod(active).invoke(verify(active, never()));
    }

    @Test
    public void evictionPinsSharedShadowsUntilIncomingAllocationHasItsOwnReference() throws ReflectiveOperationException {
        ShaderPack pack = mock(ShaderPack.class);
        PortalIrisRenderer pool = new PortalIrisRenderer(() -> pack);
        pool.beginFrame();
        PortalSharedShadows targets = mock(PortalSharedShadows.class);
        NamespacedId dimension = new NamespacedId("minecraft:overworld");
        Object shadow = shadow(targets, 50 * MIB, 1);
        shared(pool).put(dimension, shadow);
        Object older = entry(900 * MIB, 0, 0);
        field(older.getClass(), "shadows").set(older, shadow);
        entries(pool).put(1, older);
        field(PortalIrisRenderer.class, "reserved").setLong(pool, 950 * MIB);
        ProgramSet programs = mock(ProgramSet.class);
        ClientViewEnvironment environment = mock(ClientViewEnvironment.class);
        when(pack.getProgramSet(dimension)).thenReturn(programs);
        field(PortalIrisRenderer.class, "pack").set(pool, pack);
        try (MockedStatic<PortalIrisPipeline> pipelines = mockStatic(PortalIrisPipeline.class);
             MockedStatic<PortalIrisResources> resources = mockStatic(PortalIrisResources.class);
             MockedConstruction<TextureTarget> textures = mockConstruction(TextureTarget.class);
             MockedConstruction<PortalTextureScope> textureScopes = mockConstruction(PortalTextureScope.class);
             MockedStatic<PortalFramebufferScope> framebuffers = mockStatic(PortalFramebufferScope.class)) {
            pipelines.when(() -> PortalIrisPipeline.dimension(pack, environment)).thenReturn(dimension);
            resources.when(() -> PortalIrisResources.targets(programs, 512, 256)).thenReturn(100 * MIB);
            resources.when(() -> PortalIrisResources.shareShadows(programs)).thenReturn(true);
            pool.acquire(2, environment, 512, 256);
            assertEquals(150 * MIB, pool.bytes());
            assertSame(shadow, shared(pool).get(dimension));
            assertEquals(1, field(shadow.getClass(), "users").getInt(shadow));
            verify(targets, never()).close();
            pool.remove(2);
            assertEquals(0, pool.bytes());
            assertTrue(shared(pool).isEmpty());
            verify(targets).close();
            verify(textures.constructed().getFirst()).destroyBuffers();
        }
    }

    @Test
    public void allocationFailureRollsBackNewSharedReservation() throws ReflectiveOperationException {
        ShaderPack pack = mock(ShaderPack.class);
        PortalIrisRenderer pool = new PortalIrisRenderer(() -> pack);
        ProgramSet programs = mock(ProgramSet.class);
        ClientViewEnvironment environment = mock(ClientViewEnvironment.class);
        NamespacedId dimension = new NamespacedId("minecraft:overworld");
        when(pack.getProgramSet(dimension)).thenReturn(programs);
        field(PortalIrisRenderer.class, "pack").set(pool, pack);
        try (MockedStatic<PortalIrisPipeline> pipelines = mockStatic(PortalIrisPipeline.class);
             MockedStatic<PortalIrisResources> resources = mockStatic(PortalIrisResources.class);
             MockedConstruction<PortalSharedShadows> shadows = mockConstruction(PortalSharedShadows.class);
             MockedConstruction<TextureTarget> textures = mockConstruction(TextureTarget.class, (target, context) -> {
                 throw new IllegalStateException("GPU allocation failed");
             });
             MockedConstruction<PortalTextureScope> textureScopes = mockConstruction(PortalTextureScope.class);
             MockedStatic<PortalFramebufferScope> framebuffers = mockStatic(PortalFramebufferScope.class)) {
            pipelines.when(() -> PortalIrisPipeline.dimension(pack, environment)).thenReturn(dimension);
            resources.when(() -> PortalIrisResources.targets(programs, 512, 256)).thenReturn(100 * MIB);
            resources.when(() -> PortalIrisResources.shadows(programs)).thenReturn(50 * MIB);
            resources.when(() -> PortalIrisResources.shareShadows(programs)).thenReturn(true);
            assertThrows(RuntimeException.class, () -> pool.acquire(1, environment, 512, 256));
            assertEquals(0, pool.bytes());
            assertTrue(entries(pool).isEmpty());
            assertTrue(shared(pool).isEmpty());
            verify(shadows.constructed().getFirst()).close();
        }
    }

    @Test
    public void shutdownReleasesRemainingEntriesAndAccountingAfterOneCloseFails() throws ReflectiveOperationException {
        PortalIrisRenderer pool = new PortalIrisRenderer(() -> mock(ShaderPack.class));
        Object broken = entry(100 * MIB, 0, 0);
        Object remaining = entry(200 * MIB, 0, 0);
        closeMethod(broken).invoke(doThrow(new IllegalStateException("close failed")).when(broken));
        entries(pool).put(1, broken);
        entries(pool).put(2, remaining);
        field(PortalIrisRenderer.class, "reserved").setLong(pool, 300 * MIB);
        assertThrows(IllegalStateException.class, pool::close);
        assertEquals(0, pool.bytes());
        assertTrue(entries(pool).isEmpty());
        closeMethod(remaining).invoke(verify(remaining));
    }

    @Test
    public void warmupBuildsOnePipelinePerFrameAndReusesAlreadyReadySessions() throws ReflectiveOperationException {
        ShaderPack pack = mock(ShaderPack.class);
        PortalIrisRenderer pool = new PortalIrisRenderer(() -> pack);
        ClientViewEnvironment environment = mock(ClientViewEnvironment.class);
        PortalShaderContext.View view = mock(PortalShaderContext.View.class);
        NamespacedId dimension = new NamespacedId("minecraft:overworld");
        ProgramSet programs = mock(ProgramSet.class);
        when(pack.getProgramSet(dimension)).thenReturn(programs);
        field(PortalIrisRenderer.class, "pack").set(pool, pack);
        try (MockedStatic<PortalIrisPipeline> dimensions = mockStatic(PortalIrisPipeline.class);
             MockedStatic<PortalIrisResources> resources = mockStatic(PortalIrisResources.class);
             MockedConstruction<TextureTarget> textures = mockConstruction(TextureTarget.class);
             MockedConstruction<PortalIrisPipeline> pipelines = mockConstruction(PortalIrisPipeline.class);
             MockedConstruction<PortalTextureScope> textureScopes = mockConstruction(PortalTextureScope.class);
             MockedStatic<PortalFramebufferScope> framebuffers = mockStatic(PortalFramebufferScope.class)) {
            dimensions.when(() -> PortalIrisPipeline.dimension(pack, environment)).thenReturn(dimension);
            pool.beginFrame();
            PortalShaderRenderer.Session first = pool.acquire(1, environment, 512, 256);
            PortalShaderRenderer.Session second = pool.acquire(2, environment, 512, 256);
            assertFalse(first.ready());
            assertThrows(IllegalStateException.class, () -> first.begin(view));
            assertTrue(first.warm(view));
            assertTrue(first.ready());
            assertTrue(first.warm(view));
            assertFalse(second.warm(view));
            assertFalse(second.ready());
            assertEquals(1, pipelines.constructed().size());
            pool.beginFrame();
            assertTrue(second.warm(view));
            assertTrue(second.ready());
            assertEquals(2, pipelines.constructed().size());
            pool.close();
            for (PortalIrisPipeline pipeline : pipelines.constructed()) {
                verify(pipeline).close();
            }
            for (TextureTarget target : textures.constructed()) {
                verify(target).destroyBuffers();
            }
            assertEquals(0, pool.bytes());
        }
    }

    @Test
    public void steadyViewsAndResolutionChangesRetainTheirLinkedPipeline() throws ReflectiveOperationException {
        ShaderPack pack = mock(ShaderPack.class);
        PortalIrisRenderer pool = new PortalIrisRenderer(() -> pack);
        ClientViewEnvironment environment = mock(ClientViewEnvironment.class);
        ClientViewEnvironment.World world = mock(ClientViewEnvironment.World.class);
        when(environment.world()).thenReturn(world);
        when(world.dimensionKey()).thenReturn("minecraft:overworld");
        PortalShaderContext.View view = mock(PortalShaderContext.View.class);
        NamespacedId dimension = new NamespacedId("minecraft:overworld");
        ProgramSet programs = mock(ProgramSet.class);
        when(pack.getProgramSet(dimension)).thenReturn(programs);
        field(PortalIrisRenderer.class, "pack").set(pool, pack);
        try (MockedStatic<PortalIrisPipeline> dimensions = mockStatic(PortalIrisPipeline.class);
             MockedStatic<PortalIrisResources> resources = mockStatic(PortalIrisResources.class);
             MockedConstruction<TextureTarget> textures = mockConstruction(TextureTarget.class, (target, context) -> {
                 target.width = (int) context.arguments().get(1);
                 target.height = (int) context.arguments().get(2);
                 doAnswer(call -> {
                     target.width = call.getArgument(0);
                     target.height = call.getArgument(1);
                     return null;
                 }).when(target).resize(anyInt(), anyInt());
             });
             MockedConstruction<PortalIrisPipeline> pipelines = mockConstruction(PortalIrisPipeline.class);
             MockedConstruction<PortalTextureScope> textureScopes = mockConstruction(PortalTextureScope.class);
             MockedStatic<PortalFramebufferScope> framebuffers = mockStatic(PortalFramebufferScope.class)) {
            dimensions.when(() -> PortalIrisPipeline.dimension(pack, environment)).thenReturn(dimension);
            resources.when(() -> PortalIrisResources.targets(programs, 512, 256)).thenReturn(10 * MIB);
            resources.when(() -> PortalIrisResources.targets(programs, 1024, 512)).thenReturn(40 * MIB);
            pool.beginFrame();
            PortalShaderRenderer.Session session = pool.acquire(1, environment, 512, 256);
            assertTrue(session.warm(view));
            for (int frame = 0; frame < 120; frame++) {
                pool.beginFrame();
                assertSame(session, pool.acquire(1, environment, 512, 256));
                assertTrue(session.warm(view));
            }
            assertSame(session, pool.acquire(1, environment, 1024, 512));
            assertTrue(session.ready());
            assertEquals(1, pipelines.constructed().size());
            assertEquals(40 * MIB, pool.bytes());
            verify(textures.constructed().getFirst()).resize(1024, 512);
            verify(pipelines.constructed().getFirst()).resize();
            doThrow(new IllegalStateException("MRT resize failed")).when(pipelines.constructed().getFirst()).resize();
            assertThrows(IllegalStateException.class, () -> pool.acquire(1, environment, 512, 256));
            assertEquals(40 * MIB, pool.bytes());
            pool.close();
            assertEquals(0, pool.bytes());
            verify(pipelines.constructed().getFirst()).close();
        }
    }

    @Test
    public void failedWarmupConsumesTheFramesConstructionAllowance() throws ReflectiveOperationException {
        ShaderPack pack = mock(ShaderPack.class);
        PortalIrisRenderer pool = new PortalIrisRenderer(() -> pack);
        ClientViewEnvironment environment = mock(ClientViewEnvironment.class);
        PortalShaderContext.View view = mock(PortalShaderContext.View.class);
        NamespacedId dimension = new NamespacedId("minecraft:overworld");
        when(pack.getProgramSet(dimension)).thenReturn(mock(ProgramSet.class));
        field(PortalIrisRenderer.class, "pack").set(pool, pack);
        try (MockedStatic<PortalIrisPipeline> dimensions = mockStatic(PortalIrisPipeline.class);
             MockedStatic<PortalIrisResources> resources = mockStatic(PortalIrisResources.class);
             MockedConstruction<TextureTarget> textures = mockConstruction(TextureTarget.class);
             MockedConstruction<PortalIrisPipeline> pipelines = mockConstruction(PortalIrisPipeline.class, (pipeline, context) -> {
                 throw new IllegalStateException("Shader link failed");
             })) {
            dimensions.when(() -> PortalIrisPipeline.dimension(pack, environment)).thenReturn(dimension);
            pool.beginFrame();
            PortalShaderRenderer.Session failed = pool.acquire(1, environment, 512, 256);
            PortalShaderRenderer.Session pending = pool.acquire(2, environment, 512, 256);
            assertThrows(RuntimeException.class, () -> failed.warm(view));
            assertFalse(failed.ready());
            assertFalse(pending.warm(view));
            assertFalse(pending.ready());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<NamespacedId, Object> shared(PortalIrisRenderer pool) throws ReflectiveOperationException {
        return (Map<NamespacedId, Object>) field(PortalIrisRenderer.class, "shared").get(pool);
    }

    private static Object shadow(PortalSharedShadows targets, long bytes, int users) throws ReflectiveOperationException {
        Class<?> type = Class.forName(PortalIrisRenderer.class.getName() + "$Shared");
        Constructor<?> constructor = type.getDeclaredConstructor(PortalSharedShadows.class, long.class);
        constructor.setAccessible(true);
        Object shadow = constructor.newInstance(targets, bytes);
        field(type, "users").setInt(shadow, users);
        return shadow;
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Object> entries(PortalIrisRenderer pool) throws ReflectiveOperationException {
        return (Map<Integer, Object>) field(PortalIrisRenderer.class, "entries").get(pool);
    }

    private static Object entry(long bytes, long frame, int active) throws ReflectiveOperationException {
        Class<?> type = Class.forName(PortalIrisRenderer.class.getName() + "$Entry");
        Object entry = mock(type);
        field(type, "bytes").setLong(entry, bytes);
        field(type, "frame").setLong(entry, frame);
        field(type, "active").setInt(entry, active);
        return entry;
    }

    private static void reserve(PortalIrisRenderer pool, long bytes) throws ReflectiveOperationException {
        Method reserve = PortalIrisRenderer.class.getDeclaredMethod("reserve", long.class);
        reserve.setAccessible(true);
        reserve.invoke(pool, bytes);
    }

    private static Method closeMethod(Object entry) throws ReflectiveOperationException {
        Method close = entry.getClass().getDeclaredMethod("close");
        close.setAccessible(true);
        return close;
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

}
